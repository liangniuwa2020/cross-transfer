package com.crosstransfer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 前台服务：让互联状态在锁屏 / 后台 / 息屏时不被系统回收。
 *
 * v1.5.0 新增。之前只在 Activity 里持有 WakeLock 且只申请 1 小时、不续期，
 * 一旦 Activity 退到后台或锁屏时间稍长，进程就被系统冻结/回收，表现为"连上没多久就断"。
 */
class TransferForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "crosstransfer_channel"
        private const val NOTIFICATION_ID = 52021
        const val ACTION_START = "com.crosstransfer.app.START"
        const val ACTION_STOP = "com.crosstransfer.app.STOP"
        const val EXTRA_TEXT = "extra_text"

        fun start(context: Context, text: String = "互联通道已就绪，可在后台持续接收文件") {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TEXT, text)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TransferForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "互联通道已就绪"
        createChannel()
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }

        acquireLocks()
        // START_STICKY: 被系统杀掉后尝试重建
        return START_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrossTransfer::WakeLock")
            } catch (_: Exception) {}
        }
        // 关键修复: 不再写死 1 小时，而是申请无限期，前台服务存活期间一直有效
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire()
            }
        } catch (_: Exception) {}

        if (wifiLock == null) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CrossTransfer::WifiLock")
                } else {
                    wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CrossTransfer::WifiLock")
                }
            } catch (_: Exception) {}
        }
        try {
            if (wifiLock?.isHeld != true) {
                wifiLock?.setReferenceCounted(false)
                wifiLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "跨屏快传互联",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持手机与电脑/对端手机的高速传输通道"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("跨屏快传 正在运行")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }
}
