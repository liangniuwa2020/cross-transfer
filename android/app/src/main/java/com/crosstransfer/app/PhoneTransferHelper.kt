package com.crosstransfer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crosstransfer.app.databinding.DialogFolderPickerBinding
import com.crosstransfer.app.databinding.ItemFolderBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/**
 * 文件夹选择器与安卓机互传辅助工具
 */
object PhoneTransferHelper {

    fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.parseColor("#0F172A") else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 弹出本机的二维码弹窗，供另一台安卓手机扫描连接
     */
    fun showReceiveQrDialog(
        context: Context,
        server: PhoneHttpServer,
        onFolderSelectClicked: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_receive_qr, null)
        val ivQr = dialogView.findViewById<ImageView>(R.id.ivPhoneQr)
        val tvIp = dialogView.findViewById<TextView>(R.id.tvPhoneServerIp)
        val tvPath = dialogView.findViewById<TextView>(R.id.tvReceiveSavePath)
        val btnChangeDir = dialogView.findViewById<View>(R.id.btnChangeSaveDir)

        val qrPayload = server.getQrPayload()
        val bmp = generateQrBitmap(qrPayload)
        if (bmp != null) {
            ivQr.setImageBitmap(bmp)
        }

        val ip = server.getLocalIpAddress()
        tvIp.text = "本机局域网地址: $ip:${server.port}"
        val curPath = if (server.customSaveDir.isNotBlank()) {
            server.customSaveDir
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CrossTransfer").absolutePath
        }
        tvPath.text = curPath

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setPositiveButton("我知道了", null)
            .create()

        btnChangeDir.setOnClickListener {
            dialog.dismiss()
            onFolderSelectClicked()
        }

        dialog.show()
    }

    /**
     * 弹出全功能的手机内部文件夹选择器 (支持层层深入、返回上一级、快捷目录、新建文件夹)
     */
    fun showFolderPickerDialog(
        context: Context,
        initialPath: String? = null,
        onFolderSelected: (selectedPath: String) -> Unit
    ) {
        val rootDir = Environment.getExternalStorageDirectory()
        var currentDir = if (!initialPath.isNullOrBlank() && File(initialPath).exists()) {
            File(initialPath)
        } else {
            File(rootDir, "Download").takeIf { it.exists() } ?: rootDir
        }

        val dialog = BottomSheetDialog(context)
        val binding = DialogFolderPickerBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        val folderList = mutableListOf<File>()

        class FolderAdapter(private val onFolderClick: (File) -> Unit) : RecyclerView.Adapter<FolderAdapter.VH>() {
            inner class VH(val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val b = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return VH(b)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val folder = folderList[position]
                holder.b.tvFolderName.text = folder.name
                holder.b.tvFolderPath.text = folder.absolutePath
                holder.itemView.setOnClickListener {
                    onFolderClick(folder)
                }
            }

            override fun getItemCount(): Int = folderList.size
        }

        var folderAdapter: FolderAdapter? = null

        fun loadFolder(dir: File) {
            binding.tvPickerCurrentPath.text = dir.absolutePath
            val isRoot = (dir.canonicalPath == rootDir.canonicalPath || dir.absolutePath == "/storage/emulated/0")
            binding.btnPickerBack.isEnabled = !isRoot
            binding.btnPickerBack.alpha = if (!isRoot) 1.0f else 0.4f

            folderList.clear()
            val files = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            if (files != null) {
                folderList.addAll(files.sortedBy { it.name.lowercase() })
            }
            folderAdapter?.notifyDataSetChanged()

            binding.tvPickerEmpty.visibility = if (folderList.isEmpty()) View.VISIBLE else View.GONE
            binding.rvPickerFolders.visibility = if (folderList.isEmpty()) View.GONE else View.VISIBLE
        }

        folderAdapter = FolderAdapter { folder ->
            currentDir = folder
            loadFolder(currentDir)
        }
        binding.rvPickerFolders.layoutManager = LinearLayoutManager(context)
        binding.rvPickerFolders.adapter = folderAdapter

        binding.tvPickerClose.setOnClickListener { dialog.dismiss() }

        binding.btnPickerBack.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && parent.exists() && parent.absolutePath.startsWith("/storage")) {
                currentDir = parent
                loadFolder(currentDir)
            }
        }

        binding.btnPickerRoot.setOnClickListener {
            currentDir = rootDir
            loadFolder(currentDir)
        }

        binding.chipDownload.setOnClickListener {
            val d = File(rootDir, "Download")
            if (d.exists()) { currentDir = d; loadFolder(d) }
        }
        binding.chipDCIM.setOnClickListener {
            val d = File(rootDir, "DCIM")
            if (d.exists()) { currentDir = d; loadFolder(d) }
        }
        binding.chipPictures.setOnClickListener {
            val d = File(rootDir, "Pictures")
            if (d.exists()) { currentDir = d; loadFolder(d) }
        }
        binding.chipDocuments.setOnClickListener {
            val d = File(rootDir, "Documents")
            if (d.exists()) { currentDir = d; loadFolder(d) }
        }

        binding.btnPickerNewFolder.setOnClickListener {
            val etName = EditText(context).apply {
                hint = "请输入文件夹名称"
            }
            AlertDialog.Builder(context)
                .setTitle("➕ 新建子文件夹")
                .setView(etName)
                .setPositiveButton("创建") { _, _ ->
                    val name = etName.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val newDir = File(currentDir, name)
                        if (newDir.mkdirs() || newDir.exists()) {
                            Toast.makeText(context, "文件夹创建成功", Toast.LENGTH_SHORT).show()
                            loadFolder(currentDir)
                        } else {
                            Toast.makeText(context, "创建失败，请检查存储权限", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnPickerSelectCurrent.setOnClickListener {
            onFolderSelected(currentDir.absolutePath)
            dialog.dismiss()
        }

        loadFolder(currentDir)
        dialog.show()
    }
}
