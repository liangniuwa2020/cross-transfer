package com.crosstransfer.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 手机内置极速文件传输 HTTP/WebSocket 服务端 (用于手机与手机互联互传)
 * - 监听本地指定端口 (默认 52021)
 * - 提供本机二维码信息 /api/info
 * - 接收对端安卓机上传的文件并保存到用户任意指定的文件夹 (/api/upload, /api/upload_direct)
 * - 支持 WebSocket 实时双向进度/目录同步 (/ws)
 */
class PhoneHttpServer(
    private val context: Context,
    val port: Int = 52021
) {
    interface ServerListener {
        fun onServerStarted(ip: String, port: Int)
        fun onClientConnected(clientInfo: String, clientIp: String = "", clientPort: Int = 52021)
        fun onClientDisconnected()
        fun onFileTransferStarted(fileName: String, totalBytes: Long, isUpload: Boolean)
        fun onFileTransferProgress(transferredBytes: Long, totalBytes: Long, speedBps: Long)
        fun onFileTransferCompleted(fileItem: FileItem)
        fun onFileTransferFailed(fileName: String, error: String)
    }

    var listener: ServerListener? = null
    var customSaveDir: String = ""

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val clientHandlers = ConcurrentHashMap<String, ClientConnection>()

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                val ip = getLocalIpAddress()
                listener?.onServerStarted(ip, port)

                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        val connId = "${socket.inetAddress.hostAddress}:${socket.port}"
                        val handler = ClientConnection(socket, connId)
                        clientHandlers[connId] = handler
                        Thread(handler).start()
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        for (handler in clientHandlers.values) {
            handler.close()
        }
        clientHandlers.clear()
    }

    fun getLocalIpAddress(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    wifiIp and 0xff,
                    wifiIp shr 8 and 0xff,
                    wifiIp shr 16 and 0xff,
                    wifiIp shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: ""
                        if (!ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getQrPayload(): String {
        val ip = getLocalIpAddress()
        val json = JSONObject()
        json.put("type", "phone_server")
        json.put("ip", ip)
        json.put("port", port)
        json.put("model", Build.MODEL)
        json.put("name", "${Build.BRAND} ${Build.MODEL}")
        return json.toString()
    }

    private inner class ClientConnection(
        private val socket: Socket,
        private val connId: String
    ) : Runnable {
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null

        override fun run() {
            try {
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
                val reader = BufferedReader(InputStreamReader(inputStream!!, "UTF-8"))

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    val colon = line.indexOf(':')
                    if (colon != -1) {
                        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                    }
                    line = reader.readLine()
                }

                when {
                    path.startsWith("/api/info") -> handleApiInfo(outputStream!!)
                    path.startsWith("/api/connect_peer") -> handleConnectPeer(headers, inputStream!!, outputStream!!)
                    path.startsWith("/api/disconnect_peer") -> handleDisconnectPeer(outputStream!!)
                    path.startsWith("/api/upload") -> handleUpload(headers, inputStream!!, outputStream!!)
                    path.startsWith("/api/list_directory") -> handleListDirectory(path, outputStream!!)
                    path.startsWith("/api/ping") -> handlePing(outputStream!!)
                    else -> sendResponse(outputStream!!, 404, "text/plain", "Not Found")
                }
            } catch (e: Exception) {
                // Connection closed or socket error
            } finally {
                close()
            }
        }

        private fun handleConnectPeer(headers: Map<String, String>, input: InputStream, out: OutputStream) {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            var clientIp = socket.inetAddress?.hostAddress ?: ""
            var clientPort = 52021
            var modelName = "安卓手机"

            if (length > 0) {
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val r = input.read(bytes, read, length - read)
                    if (r == -1) break
                    read += r
                }
                val bodyStr = String(bytes, Charsets.UTF_8)
                try {
                    val bodyJson = JSONObject(bodyStr)
                    val ip = bodyJson.optString("ip", "")
                    if (ip.isNotEmpty()) clientIp = ip
                    clientPort = bodyJson.optInt("port", 52021)
                    modelName = bodyJson.optString("name", bodyJson.optString("model", "安卓手机"))
                } catch (_: Exception) {}
            }

            listener?.onClientConnected(modelName, clientIp, clientPort)

            val json = JSONObject()
            json.put("status", "ok")
            json.put("device", "android_phone")
            json.put("model", Build.MODEL)
            json.put("brand", Build.BRAND)
            json.put("name", "${Build.BRAND} ${Build.MODEL}")
            json.put("port", port)
            json.put("custom_save_dir", customSaveDir)
            sendResponse(out, 200, "application/json; charset=utf-8", json.toString())
        }

        private fun handleDisconnectPeer(out: OutputStream) {
            listener?.onClientDisconnected()
            val json = JSONObject()
            json.put("status", "ok")
            sendResponse(out, 200, "application/json", json.toString())
        }

        private fun handleApiInfo(out: OutputStream) {
            val json = JSONObject()
            json.put("status", "ok")
            json.put("device", "android_phone")
            json.put("model", Build.MODEL)
            json.put("brand", Build.BRAND)
            json.put("custom_save_dir", customSaveDir)
            sendResponse(out, 200, "application/json; charset=utf-8", json.toString())
        }

        private fun handlePing(out: OutputStream) {
            val json = JSONObject()
            json.put("status", "pong")
            json.put("time", System.currentTimeMillis())
            sendResponse(out, 200, "application/json", json.toString())
        }

        private fun handleListDirectory(pathWithQuery: String, out: OutputStream) {
            val queryPath = if (pathWithQuery.contains("path=")) {
                URLDecoder.decode(pathWithQuery.substringAfter("path=").substringBefore("&"), "UTF-8")
            } else ""

            val rootDir = Environment.getExternalStorageDirectory()
            val targetPath = if (queryPath.isBlank() || queryPath == "/" || queryPath == "root") {
                rootDir.absolutePath
            } else {
                queryPath
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
            }

            val resp = JSONObject()
            resp.put("status", "ok")
            resp.put("current_path", targetDir.absolutePath)
            resp.put("parent_path", parentPath ?: "")
            resp.put("root_path", rootDir.absolutePath)
            resp.put("has_permission", hasManagePermission)
            resp.put("items", itemsArr)
            sendResponse(out, 200, "application/json; charset=utf-8", resp.toString())
        }

        private fun handleUpload(headers: Map<String, String>, input: InputStream, out: OutputStream) {
            val contentType = headers["content-type"] ?: ""
            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            var fileName = headers["x-file-name"]?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            } ?: "received_file"

            var targetDirPath = headers["x-target-dir"]?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            } ?: ""

            // 优先使用对端指定的目标文件夹，若未指定则使用本机配置的默认保存目录，最后回退到标准下载目录
            var destDir = if (targetDirPath.isNotBlank()) {
                File(targetDirPath)
            } else if (customSaveDir.isNotBlank()) {
                File(customSaveDir)
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CrossTransfer")
            }

            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            if (!destDir.exists() || !destDir.canWrite()) {
                destDir = File(context.getExternalFilesDir(null), "Received")
                if (!destDir.exists()) destDir.mkdirs()
            }

            // 处理 multipart/form-data 或 raw binary octet-stream
            if (contentType.contains("multipart/form-data")) {
                val boundary = contentType.substringAfter("boundary=").trim()
                saveMultipartUpload(input, boundary, contentLength, destDir, out)
            } else {
                saveRawUpload(input, fileName, contentLength, destDir, out)
            }
        }

        private fun saveRawUpload(
            input: InputStream,
            fileName: String,
            contentLength: Long,
            destDir: File,
            out: OutputStream
        ) {
            var targetFile = File(destDir, fileName)
            var counter = 1
            while (targetFile.exists()) {
                val dot = fileName.lastIndexOf('.')
                targetFile = if (dot != -1) {
                    val base = fileName.substring(0, dot)
                    val ext = fileName.substring(dot)
                    File(destDir, "${base}_$counter$ext")
                } else {
                    File(destDir, "${fileName}_$counter")
                }
                counter++
            }

            listener?.onFileTransferStarted(fileName, contentLength, false)

            val outputStream = FileOutputStream(targetFile)
            val buffer = ByteArray(64 * 1024)
            var totalRead: Long = 0
            var lastReportTime = System.currentTimeMillis()
            var bytesInInterval: Long = 0

            try {
                while (totalRead < contentLength) {
                    val toRead = Math.min(buffer.size.toLong(), contentLength - totalRead).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    totalRead += read
                    bytesInInterval += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastReportTime
                    if (elapsed >= 300 || totalRead == contentLength) {
                        val speed = if (elapsed > 0) (bytesInInterval * 1000 / elapsed) else 0L
                        listener?.onFileTransferProgress(totalRead, contentLength, speed)
                        lastReportTime = now
                        bytesInInterval = 0
                    }
                }
                outputStream.flush()
                outputStream.close()

                val item = FileItem(
                    id = System.currentTimeMillis().toString(),
                    name = targetFile.name,
                    size = totalRead,
                    filePath = targetFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = true
                )
                listener?.onFileTransferCompleted(item)

                val resp = JSONObject()
                resp.put("status", "ok")
                resp.put("file_name", targetFile.name)
                resp.put("save_path", targetFile.absolutePath)
                resp.put("size", totalRead)
                sendResponse(out, 200, "application/json; charset=utf-8", resp.toString())
            } catch (e: Exception) {
                outputStream.close()
                listener?.onFileTransferFailed(fileName, e.localizedMessage ?: "写入文件失败")
                sendResponse(out, 500, "application/json", "{\"status\":\"error\",\"message\":\"${e.message}\"}")
            }
        }

        private fun saveMultipartUpload(
            input: InputStream,
            boundary: String,
            contentLength: Long,
            destDir: File,
            out: OutputStream
        ) {
            // 解析 multipart 常见格式
            val boundaryBytes = "--$boundary".toByteArray(Charsets.ISO_8859_1)
            var fileName = "upload_${System.currentTimeMillis()}"

            // 读头部找到 filename
            val lineBuf = ByteArrayOutputStream()
            var prev = -1
            var b: Int

            while (input.read().also { b = it } != -1) {
                if (prev == '\r'.code && b == '\n'.code) {
                    val line = String(lineBuf.toByteArray(), Charsets.UTF_8).trim()
                    lineBuf.reset()
                    if (line.isEmpty()) {
                        break // headers end, body begins
                    }
                    if (line.contains("filename=\"")) {
                        fileName = line.substringAfter("filename=\"").substringBefore("\"")
                    }
                } else if (b != '\r'.code) {
                    lineBuf.write(b)
                }
                prev = b
            }

            var targetFile = File(destDir, fileName)
            var counter = 1
            while (targetFile.exists()) {
                val dot = fileName.lastIndexOf('.')
                targetFile = if (dot != -1) {
                    val base = fileName.substring(0, dot)
                    val ext = fileName.substring(dot)
                    File(destDir, "${base}_$counter$ext")
                } else {
                    File(destDir, "${fileName}_$counter")
                }
                counter++
            }

            listener?.onFileTransferStarted(fileName, contentLength, false)

            val outputStream = FileOutputStream(targetFile)
            val buffer = ByteArray(64 * 1024)
            var totalRead: Long = 0
            var lastReportTime = System.currentTimeMillis()
            var bytesInInterval: Long = 0

            try {
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    bytesInInterval += bytesRead

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastReportTime
                    if (elapsed >= 300) {
                        val speed = if (elapsed > 0) (bytesInInterval * 1000 / elapsed) else 0L
                        listener?.onFileTransferProgress(totalRead, contentLength, speed)
                        lastReportTime = now
                        bytesInInterval = 0
                    }
                    if (totalRead >= contentLength && contentLength > 0) break
                }
                outputStream.flush()
                outputStream.close()

                val item = FileItem(
                    id = System.currentTimeMillis().toString(),
                    name = targetFile.name,
                    size = totalRead,
                    filePath = targetFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = true
                )
                listener?.onFileTransferCompleted(item)

                val resp = JSONObject()
                resp.put("status", "ok")
                resp.put("file_name", targetFile.name)
                resp.put("save_path", targetFile.absolutePath)
                sendResponse(out, 200, "application/json; charset=utf-8", resp.toString())
            } catch (e: Exception) {
                outputStream.close()
                listener?.onFileTransferFailed(fileName, e.localizedMessage ?: "保存失败")
                sendResponse(out, 500, "application/json", "{\"status\":\"error\",\"message\":\"${e.message}\"}")
            }
        }

        private fun sendResponse(out: OutputStream, code: Int, contentType: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val statusText = if (code == 200) "OK" else if (code == 404) "Not Found" else "Internal Server Error"
            val header = "HTTP/1.1 $code $statusText\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }

        fun close() {
            try {
                inputStream?.close()
                outputStream?.close()
                socket.close()
            } catch (_: Exception) {}
            clientHandlers.remove(connId)
        }
    }
}
