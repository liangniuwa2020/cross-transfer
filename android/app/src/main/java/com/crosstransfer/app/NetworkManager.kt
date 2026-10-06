package com.crosstransfer.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import okio.Okio
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class NetworkManager(private val context: Context) {

    interface ConnectionListener {
        fun onConnected(serverName: String, host: String, port: Int)
        fun onDisconnected(reason: String)
        fun onFileTransferStarted(fileName: String, totalBytes: Long, isUpload: Boolean)
        fun onFileTransferProgress(transferredBytes: Long, totalBytes: Long, speedBps: Long)
        fun onFileTransferCompleted(fileItem: FileItem)
        fun onFileTransferFailed(fileName: String, error: String)
        fun onOpenPickerRequested()
        fun onRequestStoragePermission()
    }

    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websockets & big downloads
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS) // OkHttp native WebSocket PING frame every 10s to keep NAT/AP alive
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    var serverHost: String = ""
        private set
    var serverPort: Int = 52020
        private set
    var isConnected: Boolean = false
        private set

    private var autoReconnectEnabled = false
    private var reconnectAttempts = 0
    private var lastPin: String = ""

    var listener: ConnectionListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val appPingRunnable = object : Runnable {
        override fun run() {
            if (isConnected && webSocket != null) {
                try {
                    webSocket?.send("ping")
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 15000)
            }
        }
    }

    fun connect(host: String, port: Int, pin: String = "") {
        autoReconnectEnabled = true
        reconnectAttempts = 0
        lastPin = pin
        serverHost = host
        serverPort = port
        internalConnect(host, port, pin)
    }

    private fun internalConnect(host: String, port: Int, pin: String) {
        closeWebSocketInternal()

        val wsUrl = "ws://$host:$port/ws?device=android&model=${android.os.Build.MODEL}&pin=$pin"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempts = 0
                mainHandler.removeCallbacks(appPingRunnable)
                mainHandler.post(appPingRunnable)
                mainHandler.post {
                    listener?.onConnected("Windows PC", host, port)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("action")) {
                        "device_info" -> {
                            val pcName = json.optString("name", "Windows PC")
                            mainHandler.post {
                                listener?.onConnected(pcName, host, port)
                            }
                        }
                        "send_file_to_phone" -> {
                            val fileId = json.optString("id")
                            val fileName = json.optString("name")
                            val fileSize = json.optLong("size")
                            val targetDir = json.optString("target_dir", "")
                            val downloadUrl = "http://$host:$port/api/download/$fileId"
                            downloadFile(downloadUrl, fileName, fileSize, targetDir)
                        }
                        "disconnect_peer" -> {
                            val reason = json.optString("reason", "电脑端主动断开")
                            disconnect(reason)
                        }
                        "list_storage" -> {
                            reportStorageFiles()
                        }
                        "list_directory" -> {
                            val path = json.optString("path", "")
                            listDirectory(path)
                        }
                        "request_storage_permission" -> {
                            mainHandler.post {
                                listener?.onRequestStoragePermission()
                            }
                        }
                        "open_file_picker" -> {
                            mainHandler.post {
                                listener?.onOpenPickerRequested()
                            }
                        }
                        "send_file_to_pc" -> {
                            val path = json.optString("path")
                            if (path.isNotEmpty()) {
                                uploadPath(path)
                            }
                        }
                        "create_directory" -> {
                            val path = json.optString("path")
                            handleCreateDirectory(path)
                        }
                        "delete_file" -> {
                            val path = json.optString("path")
                            handleDeleteFile(path)
                        }
                        "rename_file" -> {
                            val path = json.optString("path")
                            val newName = json.optString("new_name")
                            handleRenameFile(path, newName)
                        }
                        "copy_file" -> {
                            val sourcePath = json.optString("source_path")
                            val targetDir = json.optString("target_dir")
                            handleCopyFile(sourcePath, targetDir)
                        }
                        "move_file" -> {
                            val sourcePath = json.optString("source_path")
                            val targetDir = json.optString("target_dir")
                            handleMoveFile(sourcePath, targetDir)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                mainHandler.removeCallbacks(appPingRunnable)
                if (autoReconnectEnabled && code != 1000) {
                    scheduleReconnect("网络连接关闭，正在尝试重连...")
                } else {
                    mainHandler.post {
                        listener?.onDisconnected(reason.ifEmpty { "连接已关闭" })
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                mainHandler.removeCallbacks(appPingRunnable)
                if (autoReconnectEnabled) {
                    scheduleReconnect("连接中断，正在自动恢复...")
                } else {
                    mainHandler.post {
                        listener?.onDisconnected("连接失败: ${t.localizedMessage ?: "无法连接到电脑"}")
                    }
                }
            }
        })
    }

    private fun scheduleReconnect(notice: String) {
        if (!autoReconnectEnabled || serverHost.isEmpty()) return
        reconnectAttempts++
        val delayMs = if (reconnectAttempts <= 3) 2000L else if (reconnectAttempts <= 10) 4000L else 8000L
        mainHandler.post {
            listener?.onDisconnected("$notice (第${reconnectAttempts}次重试)")
        }
        mainHandler.postDelayed({
            if (autoReconnectEnabled && !isConnected && serverHost.isNotEmpty()) {
                internalConnect(serverHost, serverPort, lastPin)
            }
        }, delayMs)
    }

    private fun closeWebSocketInternal() {
        mainHandler.removeCallbacks(appPingRunnable)
        try {
            webSocket?.close(1000, null)
        } catch (_: Exception) {}
        webSocket = null
        isConnected = false
    }

    fun disconnect(reason: String = "用户断开") {
        autoReconnectEnabled = false
        closeWebSocketInternal()
        mainHandler.post {
            listener?.onDisconnected(reason)
        }
    }

    fun disconnectByUser() {
        autoReconnectEnabled = false
        try {
            val json = JSONObject()
            json.put("action", "phone_disconnect")
            webSocket?.send(json.toString())
            webSocket?.close(1000, "手机主动断开")
        } catch (_: Exception) {}
        closeWebSocketInternal()
        mainHandler.post {
            listener?.onDisconnected("已主动断开连接")
        }
    }

    private fun reportStorageFiles() {
        Thread {
            val filesArr = org.json.JSONArray()
            try {
                // 1. App Received Files (100% accessible on all Android versions)
                val receivedDir = File(context.getExternalFilesDir(null), "Received")
                if (receivedDir.exists()) {
                    receivedDir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            val obj = JSONObject()
                            obj.put("name", f.name)
                            obj.put("size", f.length())
                            obj.put("path", f.absolutePath)
                            obj.put("modified", f.lastModified() / 1000)
                            filesArr.put(obj)
                        }
                    }
                }

                // 2. Query MediaStore for Downloads / Photos / Documents safely without ScopedStorage exceptions
                try {
                    val projection = arrayOf(
                        android.provider.MediaStore.MediaColumns._ID,
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.SIZE,
                        android.provider.MediaStore.MediaColumns.DATE_MODIFIED
                    )
                    val contentUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    } else {
                        android.provider.MediaStore.Files.getContentUri("external")
                    }

                    context.contentResolver.query(
                        contentUri,
                        projection,
                        null,
                        null,
                        "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                        val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)

                        var count = 0
                        while (cursor.moveToNext() && count < 25) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "unnamed"
                            val size = cursor.getLong(sizeCol)
                            val date = cursor.getLong(dateCol)
                            val itemUri = android.content.ContentUris.withAppendedId(contentUri, id)

                            val obj = JSONObject()
                            obj.put("name", name)
                            obj.put("size", size)
                            obj.put("path", itemUri.toString())
                            obj.put("modified", date)
                            filesArr.put(obj)
                            count++
                        }
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // ALWAYS send storage_list so PC never gets stuck on loading!
                try {
                    val msg = JSONObject()
                    msg.put("action", "storage_list")
                    msg.put("files", filesArr)
                    webSocket?.send(msg.toString())
                } catch (_: Exception) {}
            }
        }.start()
    }

    fun listDirectory(requestedPath: String?) {
        Thread {
            val rootDir = Environment.getExternalStorageDirectory()
            val targetPath = if (requestedPath.isNullOrBlank() || requestedPath == "/" || requestedPath == "root") {
                rootDir.absolutePath
            } else {
                requestedPath
            }
            val targetDir = File(targetPath)
            val isRoot = (targetDir.canonicalPath == rootDir.canonicalPath || targetDir.absolutePath == "/storage/emulated/0")
            val parentPath = if (isRoot) null else targetDir.parentFile?.absolutePath

            val itemsArr = JSONArray()
            val hasManagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }

            try {
                if (targetDir.exists() && targetDir.isDirectory) {
                    val fileList = targetDir.listFiles()
                    if (fileList != null) {
                        val sorted = fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        for (f in sorted) {
                            if (f.name.startsWith(".")) continue
                            val item = JSONObject()
                            item.put("name", f.name)
                            item.put("path", f.absolutePath)
                            item.put("is_dir", f.isDirectory)
                            item.put("size", if (f.isFile) f.length() else 0L)
                            item.put("modified", f.lastModified() / 1000)
                            if (f.isDirectory) {
                                val subCount = try { f.list { _, name -> !name.startsWith(".") }?.size ?: 0 } catch (_: Exception) { 0 }
                                item.put("count", subCount)
                            }
                            itemsArr.put(item)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    val resp = JSONObject()
                    resp.put("action", "directory_content")
                    resp.put("current_path", targetDir.absolutePath)
                    resp.put("parent_path", parentPath ?: "")
                    resp.put("root_path", rootDir.absolutePath)
                    resp.put("has_permission", hasManagePermission)
                    resp.put("items", itemsArr)
                    webSocket?.send(resp.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.start()
    }

    private fun sendOperationResult(op: String, success: Boolean, message: String, refreshPath: String? = null) {
        Thread {
            try {
                val resp = JSONObject()
                resp.put("action", "operation_result")
                resp.put("operation", op)
                resp.put("success", success)
                resp.put("message", message)
                resp.put("refresh_path", refreshPath ?: "")
                webSocket?.send(resp.toString())
                if (!refreshPath.isNullOrBlank()) {
                    listDirectory(refreshPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun handleCreateDirectory(path: String) {
        Thread {
            try {
                val dir = File(path)
                val success = dir.exists() || dir.mkdirs()
                sendOperationResult("create_directory", success, if (success) "文件夹创建成功" else "创建文件夹失败", dir.parent ?: path)
            } catch (e: Exception) {
                sendOperationResult("create_directory", false, e.localizedMessage ?: "创建失败", null)
            }
        }.start()
    }

    private fun handleDeleteFile(path: String) {
        Thread {
            try {
                val file = File(path)
                val parent = file.parent ?: "/storage/emulated/0"
                val success = if (file.exists()) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                } else false
                sendOperationResult("delete", success, if (success) "删除成功" else "文件不存在或无权删除", parent)
            } catch (e: Exception) {
                sendOperationResult("delete", false, e.localizedMessage ?: "删除失败", null)
            }
        }.start()
    }

    private fun handleRenameFile(path: String, newName: String) {
        Thread {
            try {
                val oldFile = File(path)
                val newFile = File(oldFile.parentFile, newName)
                val success = oldFile.exists() && oldFile.renameTo(newFile)
                sendOperationResult("rename", success, if (success) "重命名成功" else "重命名失败，新名称可能冲突", oldFile.parent ?: "/storage/emulated/0")
            } catch (e: Exception) {
                sendOperationResult("rename", false, e.localizedMessage ?: "重命名失败", null)
            }
        }.start()
    }

    private fun handleCopyFile(sourcePath: String, targetDir: String) {
        Thread {
            try {
                val source = File(sourcePath)
                val destDir = File(targetDir)
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, source.name)
                val success = try {
                    if (source.isDirectory) {
                        source.copyRecursively(dest, overwrite = true)
                    } else {
                        source.copyTo(dest, overwrite = true)
                    }
                    true
                } catch (e: Exception) {
                    false
                }
                sendOperationResult("copy", success, if (success) "复制成功" else "复制失败", targetDir)
            } catch (e: Exception) {
                sendOperationResult("copy", false, e.localizedMessage ?: "复制失败", targetDir)
            }
        }.start()
    }

    private fun handleMoveFile(sourcePath: String, targetDir: String) {
        Thread {
            try {
                val source = File(sourcePath)
                val destDir = File(targetDir)
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, source.name)
                var success = source.renameTo(dest)
                if (!success) {
                    try {
                        if (source.isDirectory) {
                            source.copyRecursively(dest, overwrite = true)
                            source.deleteRecursively()
                        } else {
                            source.copyTo(dest, overwrite = true)
                            source.delete()
                        }
                        success = true
                    } catch (e: Exception) {
                        success = false
                    }
                }
                sendOperationResult("move", success, if (success) "移动成功" else "移动失败", targetDir)
            } catch (e: Exception) {
                sendOperationResult("move", false, e.localizedMessage ?: "移动失败", targetDir)
            }
        }.start()
    }

    fun uploadPath(pathStr: String) {
        try {
            val file = File(pathStr)
            if (file.exists() && file.isFile) {
                sendFileDirect(file)
            } else if (pathStr.startsWith("content://")) {
                sendFile(Uri.parse(pathStr))
            } else {
                sendFile(Uri.fromFile(file))
            }
        } catch (e: Exception) {
            listener?.onFileTransferFailed("上传失败", e.localizedMessage ?: "文件路径错误")
        }
    }

    fun sendFileDirect(file: File) {
        if (!isConnected) {
            listener?.onFileTransferFailed(file.name, "未连接到电脑")
            return
        }

        Thread {
            try {
                val fileName = file.name
                val fileSize = file.length()

                mainHandler.post {
                    listener?.onFileTransferStarted(fileName, fileSize, true)
                }

                val uploadUrl = "http://$serverHost:$serverPort/api/upload"

                val requestBody = object : RequestBody() {
                    override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength(): Long = fileSize
                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalUploaded: Long = 0
                        var lastReportTime = System.currentTimeMillis()
                        var bytesInInterval: Long = 0

                        FileInputStream(file).use { stream ->
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                sink.write(buffer, 0, bytesRead)
                                totalUploaded += bytesRead
                                bytesInInterval += bytesRead

                                val now = System.currentTimeMillis()
                                val elapsed = now - lastReportTime
                                if (elapsed >= 300 || totalUploaded == fileSize) {
                                    val speed = if (elapsed > 0) (bytesInInterval * 1000 / elapsed) else 0L
                                    val currentTotal = totalUploaded
                                    mainHandler.post {
                                        listener?.onFileTransferProgress(currentTotal, fileSize, speed)
                                    }
                                    lastReportTime = now
                                    bytesInInterval = 0
                                }
                            }
                        }
                    }
                }

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, requestBody)
                    .build()

                val request = Request.Builder()
                    .url(uploadUrl)
                    .post(multipartBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val item = FileItem(
                        id = System.currentTimeMillis().toString(),
                        name = fileName,
                        size = fileSize,
                        filePath = file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        isIncoming = false
                    )
                    mainHandler.post {
                        listener?.onFileTransferCompleted(item)
                    }
                } else {
                    mainHandler.post {
                        listener?.onFileTransferFailed(fileName, "PC响应错误: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    listener?.onFileTransferFailed(file.name, e.localizedMessage ?: "未知网络错误")
                }
            }
        }.start()
    }

    fun sendFile(uri: Uri) {
        if (!isConnected) {
            listener?.onFileTransferFailed("未知文件", "未连接到电脑")
            return
        }

        Thread {
            try {
                val fileName = getFileName(uri) ?: "transfer_${System.currentTimeMillis()}"
                val fileSize = getFileSize(uri)

                mainHandler.post {
                    listener?.onFileTransferStarted(fileName, fileSize, true)
                }

                val uploadUrl = "http://$serverHost:$serverPort/api/upload"

                val requestBody = object : RequestBody() {
                    override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()

                    override fun contentLength(): Long = fileSize

                    override fun writeTo(sink: BufferedSink) {
                        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("无法读取文件: $uri")

                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalUploaded: Long = 0
                        var lastReportTime = System.currentTimeMillis()
                        var bytesInInterval: Long = 0

                        inputStream.use { stream ->
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                sink.write(buffer, 0, bytesRead)
                                totalUploaded += bytesRead
                                bytesInInterval += bytesRead

                                val now = System.currentTimeMillis()
                                val elapsed = now - lastReportTime
                                if (elapsed >= 300 || totalUploaded == fileSize) {
                                    val speed = if (elapsed > 0) (bytesInInterval * 1000 / elapsed) else 0L
                                    val currentTotal = totalUploaded
                                    mainHandler.post {
                                        listener?.onFileTransferProgress(currentTotal, fileSize, speed)
                                    }
                                    lastReportTime = now
                                    bytesInInterval = 0
                                }
                            }
                        }
                    }
                }

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, requestBody)
                    .build()

                val request = Request.Builder()
                    .url(uploadUrl)
                    .post(multipartBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val item = FileItem(
                        id = System.currentTimeMillis().toString(),
                        name = fileName,
                        size = fileSize,
                        filePath = uri.toString(),
                        timestamp = System.currentTimeMillis(),
                        isIncoming = false
                    )
                    mainHandler.post {
                        listener?.onFileTransferCompleted(item)
                    }
                } else {
                    mainHandler.post {
                        listener?.onFileTransferFailed(fileName, "PC响应错误: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    listener?.onFileTransferFailed("传输失败", e.localizedMessage ?: "未知网络错误")
                }
            }
        }.start()
    }

    private fun downloadFile(url: String, fileName: String, fileSize: Long, targetDirPath: String? = null) {
        Thread {
            try {
                mainHandler.post {
                    listener?.onFileTransferStarted(fileName, fileSize, false)
                }

                var downloadDir = if (!targetDirPath.isNullOrBlank()) {
                    val dir = File(targetDirPath)
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                    if (dir.exists() && dir.canWrite()) {
                        dir
                    } else {
                        File(context.getExternalFilesDir(null), "Received")
                    }
                } else {
                    File(context.getExternalFilesDir(null), "Received")
                }
                if (!downloadDir.exists()) downloadDir.mkdirs()

                var targetFile = File(downloadDir, fileName)
                targetFile.parentFile?.mkdirs()
                var fileIndex = 1
                while (targetFile.exists()) {
                    val dotIdx = fileName.lastIndexOf('.')
                    targetFile = if (dotIdx != -1) {
                        val base = fileName.substring(0, dotIdx)
                        val ext = fileName.substring(dotIdx)
                        File(downloadDir, "${base}_$fileIndex$ext")
                    } else {
                        File(downloadDir, "${fileName}_$fileIndex")
                    }
                    fileIndex++
                }

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful || response.body == null) {
                    mainHandler.post {
                        listener?.onFileTransferFailed(fileName, "下载失败: HTTP ${response.code}")
                    }
                    return@Thread
                }

                val body = response.body!!
                val actualSize = if (fileSize > 0) fileSize else body.contentLength()

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalDownloaded: Long = 0
                var lastReportTime = System.currentTimeMillis()
                var bytesInInterval: Long = 0

                inputStream.use { input ->
                    outputStream.use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalDownloaded += bytesRead
                            bytesInInterval += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastReportTime
                            if (elapsed >= 300 || totalDownloaded == actualSize) {
                                val speed = if (elapsed > 0) (bytesInInterval * 1000 / elapsed) else 0L
                                val currentTotal = totalDownloaded
                                mainHandler.post {
                                    listener?.onFileTransferProgress(currentTotal, actualSize, speed)
                                }
                                lastReportTime = now
                                bytesInInterval = 0
                            }
                        }
                    }
                }

                val item = FileItem(
                    id = System.currentTimeMillis().toString(),
                    name = targetFile.name,
                    size = targetFile.length(),
                    filePath = targetFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = true
                )
                mainHandler.post {
                    listener?.onFileTransferCompleted(item)
                }
                if (!targetDirPath.isNullOrBlank()) {
                    listDirectory(targetDirPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    listener?.onFileTransferFailed(fileName, e.localizedMessage ?: "下载失败")
                }
            }
        }.start()
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun getFileSize(uri: Uri): Long {
        var size: Long = -1
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex >= 0) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size <= 0) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    size = stream.available().toLong()
                }
            } catch (_: Exception) {}
        }
        return size
    }
}
