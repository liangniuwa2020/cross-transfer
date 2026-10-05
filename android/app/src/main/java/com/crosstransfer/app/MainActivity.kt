package com.crosstransfer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.crosstransfer.app.databinding.ActivityMainBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.net.URI
import java.util.LinkedList

class MainActivity : AppCompatActivity(), NetworkManager.ConnectionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var networkManager: NetworkManager
    private lateinit var fileAdapter: FileAdapter
    private val fileList = mutableListOf<FileItem>()
    private val pendingUrisToSend = LinkedList<Uri>()
    private var isSendingQueue = false

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScanResult(result.contents)
        } else {
            Toast.makeText(this, "已取消扫码", Toast.LENGTH_SHORT).show()
        }
    }

    private val selectFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            enqueueFilesToSend(uris)
        }
    }

    private val selectMediaLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            enqueueFilesToSend(uris)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            launchQrScanner()
        } else {
            Toast.makeText(this, "需要相机权限以扫描电脑二维码", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkManager = NetworkManager(this)
        networkManager.listener = this

        setupRecyclerView()
        setupListeners()
        updateConnectionUi(false, "未连接电脑", "")
    }

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(this, fileList)
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = fileAdapter
        updateEmptyView()
    }

    private fun setupListeners() {
        binding.btnScanQr.setOnClickListener {
            checkCameraAndScan()
        }

        binding.btnManualConnect.setOnClickListener {
            val ipText = binding.etManualIp.text.toString().trim()
            if (ipText.isEmpty()) {
                Toast.makeText(this, "请输入电脑IP地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var host = ipText
            var port = 52020
            if (ipText.contains(":")) {
                val parts = ipText.split(":")
                host = parts[0]
                port = parts[1].toIntOrNull() ?: 52020
            }
            binding.tvConnectionStatus.text = "正在连接 $host:$port ..."
            networkManager.connect(host, port)
        }

        binding.tvDisconnect.setOnClickListener {
            networkManager.disconnectByUser()
        }

        binding.btnOpenPhoneStorage.setOnClickListener {
            if (!networkManager.isConnected) {
                Toast.makeText(this, "请先扫描电脑二维码建立互联！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectFilesLauncher.launch(arrayOf("*/*"))
        }

        binding.btnManualDisconnect.setOnClickListener {
            networkManager.disconnectByUser()
        }

        binding.btnSendAnyFile.setOnClickListener {
            if (!networkManager.isConnected) {
                Toast.makeText(this, "请先扫描电脑二维码建立互联！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectFilesLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSendMedia.setOnClickListener {
            if (!networkManager.isConnected) {
                Toast.makeText(this, "请先扫描电脑二维码建立互联！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectMediaLauncher.launch("image/*")
        }

        binding.btnGrantManageStorage.setOnClickListener {
            checkAndRequestManageStorage()
        }

        binding.btnClearHistory.setOnClickListener {
            fileAdapter.clearAll()
            updateEmptyView()
        }
    }

    private fun checkCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchQrScanner()
        } else {
            val permissions = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setPrompt("对准电脑屏幕上的互联二维码")
            setBeepEnabled(true)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        qrScannerLauncher.launch(options)
    }

    private fun handleScanResult(rawContent: String) {
        val content = rawContent.trim()
        var host = ""
        var port = 52020
        var pin = ""

        try {
            if (content.startsWith("{")) {
                val json = JSONObject(content)
                host = json.optString("ip")
                if (host.isEmpty() && json.has("ips")) {
                    val ipsArr = json.getJSONArray("ips")
                    if (ipsArr.length() > 0) host = ipsArr.getString(0)
                }
                port = json.optInt("port", 52020)
                pin = json.optString("pin", "")
            } else if (content.startsWith("http://") || content.startsWith("https://")) {
                val uri = URI(content)
                host = uri.host ?: ""
                port = if (uri.port > 0) uri.port else 52020
                val query = uri.query ?: ""
                if (query.contains("pin=")) {
                    pin = query.substringAfter("pin=").substringBefore("&")
                }
            } else if (content.contains(":")) {
                val parts = content.split(":")
                host = parts[0]
                port = parts[1].toIntOrNull() ?: 52020
            } else {
                host = content
            }

            if (host.isNotEmpty()) {
                binding.tvConnectionStatus.text = "正在连接电脑 $host:$port ..."
                networkManager.connect(host, port, pin)
            } else {
                Toast.makeText(this, "无效的二维码内容", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "解析二维码失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueFilesToSend(uris: List<Uri>) {
        pendingUrisToSend.addAll(uris)
        if (!isSendingQueue) {
            processNextFileInQueue()
        }
    }

    private fun processNextFileInQueue() {
        if (pendingUrisToSend.isEmpty()) {
            isSendingQueue = false
            return
        }
        isSendingQueue = true
        val nextUri = pendingUrisToSend.poll()
        if (nextUri != null) {
            networkManager.sendFile(nextUri)
        } else {
            isSendingQueue = false
        }
    }

    private fun updateConnectionUi(connected: Boolean, title: String, subtitle: String) {
        binding.tvConnectionStatus.text = title
        binding.tvPcAddress.text = subtitle.ifEmpty {
            if (connected) "局域网已互通，支持高速双向传输" else "电脑端打开配套软件，将自动生成连接二维码"
        }

        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (connected) "#22C55E" else "#EF4444"))
        }
        binding.viewStatusDot.background = dot

        binding.tvDisconnect.visibility = if (connected) View.VISIBLE else View.GONE
        binding.layoutConnectedActions.visibility = if (connected) View.VISIBLE else View.GONE
        binding.layoutManualConnect.visibility = if (connected) View.GONE else View.VISIBLE
        binding.btnScanQr.text = if (connected) "重新扫码" else "📷 扫码互联"
        binding.btnScanQr.setBackgroundColor(Color.parseColor(if (connected) "#059669" else "#2563EB"))
        updateStorageBanner()
    }

    override fun onResume() {
        super.onResume()
        updateStorageBanner()
    }

    private fun updateStorageBanner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasAccess = Environment.isExternalStorageManager()
            binding.layoutManageStorageBanner.visibility = if (!hasAccess && networkManager.isConnected) View.VISIBLE else View.GONE
        } else {
            binding.layoutManageStorageBanner.visibility = View.GONE
        }
    }

    fun checkAndRequestManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "请开启【所有文件访问权限】，以便电脑端自由浏览手机各文件夹", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            } else {
                Toast.makeText(this, "✅ 所有文件管理权限已开启，电脑端可自由访问手机各目录", Toast.LENGTH_SHORT).show()
                updateStorageBanner()
            }
        } else {
            Toast.makeText(this, "存储权限已满足", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateEmptyView() {
        binding.tvEmptyHistory.visibility = if (fileList.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFiles.visibility = if (fileList.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onConnected(serverName: String, host: String, port: Int) {
        updateConnectionUi(true, "🟢 已连接到: $serverName", "电脑地址: $host:$port (连接正常)")
        Toast.makeText(this, "已成功互联电脑: $serverName", Toast.LENGTH_SHORT).show()
    }

    override fun onDisconnected(reason: String) {
        updateConnectionUi(false, "🔴 未连接电脑 ($reason)", "请重新点击右上角扫码连接")
    }

    override fun onFileTransferStarted(fileName: String, totalBytes: Long, isUpload: Boolean) {
        binding.cardProgress.visibility = View.VISIBLE
        val dirText = if (isUpload) "正在发送给电脑" else "正在接收来自电脑"
        binding.tvTransferFilename.text = "$dirText: $fileName"
        binding.progressBar.progress = 0
        binding.tvTransferPercent.text = "0%"
        binding.tvTransferSpeed.text = "正在准备传输..."
    }

    override fun onFileTransferProgress(transferredBytes: Long, totalBytes: Long, speedBps: Long) {
        val percent = if (totalBytes > 0) ((transferredBytes * 100) / totalBytes).toInt() else 0
        binding.progressBar.progress = percent
        binding.tvTransferPercent.text = "$percent%"

        val speedMb = speedBps.toDouble() / (1024 * 1024)
        val speedStr = String.format("%.2f MB/s", speedMb)
        val transferredMb = transferredBytes.toDouble() / (1024 * 1024)
        val totalMb = totalBytes.toDouble() / (1024 * 1024)

        binding.tvTransferSpeed.text = String.format("速度: %s • 已传输: %.1f MB / %.1f MB", speedStr, transferredMb, totalMb)
    }

    override fun onFileTransferCompleted(fileItem: FileItem) {
        binding.cardProgress.visibility = View.GONE
        fileAdapter.addFirst(fileItem)
        updateEmptyView()
        Toast.makeText(this, "传输完成: ${fileItem.name}", Toast.LENGTH_SHORT).show()

        if (isSendingQueue && pendingUrisToSend.isNotEmpty()) {
            processNextFileInQueue()
        } else {
            isSendingQueue = false
        }
    }

    override fun onFileTransferFailed(fileName: String, error: String) {
        binding.cardProgress.visibility = View.GONE
        Toast.makeText(this, "文件传输失败: $error", Toast.LENGTH_LONG).show()

        if (isSendingQueue && pendingUrisToSend.isNotEmpty()) {
            processNextFileInQueue()
        } else {
            isSendingQueue = false
        }
    }

    override fun onOpenPickerRequested() {
        runOnUiThread {
            Toast.makeText(this, "电脑端请求进入手机存储，请选择要发送的文件", Toast.LENGTH_SHORT).show()
            selectFilesLauncher.launch(arrayOf("*/*"))
        }
    }

    override fun onRequestStoragePermission() {
        runOnUiThread {
            checkAndRequestManageStorage()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkManager.disconnect("应用关闭")
    }
}
