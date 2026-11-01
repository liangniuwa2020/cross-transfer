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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机内置极速文件传输 HTTP 服务端 (用于手机与手机互联互传)
 * - 监听本地指定端口 (默认 52021)
 * - 提供本机二维码信息 /api/info
 * - 接收对端安卓机上传的文件并保存到用户任意指定的文件夹 (/api/upload)
 * - 支持目录浏览 /api/list_directory 与心跳 /api/ping
 *
 * v1.5.0 关键修复:
 * 1) 请求头改为直接按字节解析，不再用 BufferedReader —— 原先 BufferedReader 会把
 *    body 的前若干字节预读进自己的缓冲区，导致随后从裸 InputStream 读 body 时丢失开头字节，
 *    大文件必然损坏（这是"传输经常失败/文件打不开"的根因）。
 * 2) multipart 解析重写为按 boundary 流式截断 —— 原先会把结尾的 `\r\n--boundary--\r\n`
 *    一并写进文件，并错误地用整体 Content-Length 判断结束。
 * 3) 每个连接设置读超时，避免半开连接长期占用线程。
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
    private val connSeq = AtomicInteger(0)

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                val ip = getLocalIpAddress()
                listener?.onServerStarted(ip, port)

                while (isRunning) {
                    try {
                        val socket = ss.accept() ?: break
                        socket.tcpNoDelay = true
                        socket.soTimeout = 60_000
                        val connId = "c${connSeq.incrementAndGet()}@${socket.inetAddress.hostAddress}"
                        val handler = ClientConnection(socket, connId)
                        clientHandlers[connId] = handler
                        Thread(handler, "ct-conn-$connId").start()
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

    /**
     * 获取本机所有可用的 IPv4 地址列表 (优先局域网 IP，如 WiFi、热点等)
     */
    fun getAllLocalIpAddresses(): List<String> {
        val ips = linkedSetOf<String>()

        // 1. 尝试从 WifiManager 获取
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                val ipStr = String.format(
                    "%d.%d.%d.%d",
                    wifiIp and 0xff,
                    wifiIp shr 8 and 0xff,
                    wifiIp shr 16 and 0xff,
                    wifiIp shr 24 and 0xff
                )
                if (ipStr != "0.0.0.0" && ipStr != "127.0.0.1") {
                    ips.add(ipStr)
                }
            }
        } catch (_: Exception) {}

        // 2. 遍历所有网络接口 (涵盖 wlan0, ap0 热点网卡, rndis0 等)
        val hotspotIps = mutableListOf<String>()
        val wlanIps = mutableListOf<String>()
        val otherIps = mutableListOf<String>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.isNotBlank() && !ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                            when {
                                name.contains("ap") || name.contains("softap") -> hotspotIps.add(ip)
                                name.contains("wlan") -> wlanIps.add(ip)
                                else -> otherIps.add(ip)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 按优先级汇总: 先热点(若开启热点)，后无线局域网，再其他
        ips.addAll(hotspotIps)
        ips.addAll(wlanIps)
        ips.addAll(otherIps)

        if (ips.isEmpty()) {
            ips.add("127.0.0.1")
        }
        return ips.toList()
    }

    fun getLocalIpAddress(): String {
        val all = getAllLocalIpAddresses()
        return all.firstOrNull { it != "127.0.0.1" } ?: all.firstOrNull() ?: "127.0.0.1"
    }

    fun getQrPayload(): String {
        val allIps = getAllLocalIpAddresses()
        val primaryIp = getLocalIpAddress()
        val json = JSONObject()
        json.put("type", "phone_server")
        json.put("ip", primaryIp)
        val ipsArray = JSONArray()
        allIps.forEach { ipsArray.put(it) }
        json.put("ips", ipsArray)
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
                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                inputStream = input
                outputStream = socket.getOutputStream()

                // 逐字节读取请求头，直到 \r\n\r\n —— 绝不越过 body 起点
                val headerBytes = ByteArrayOutputStream(2048)
                var state = 0
                var b = input.read()
                while (b != -1) {
                    headerBytes.write(b)
                    state = when {
                        (state == 0 || state == 2) && b == '\r'.code -> state + 1
                        (state == 1 || state == 3) && b == '\n'.code -> state + 1
                        b == '\r'.code -> 1
                        else -> 0
                    }
                    if (state == 4) break
                    b = input.read()
                }
                if (state != 4) return

                val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
                val lines = headerText.split("\r\n")
                if (lines.isEmpty()) return

                val parts = lines[0].split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.isBlank()) continue
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                    }
                }

                when {
                    path.startsWith("/api/info") -> handleApiInfo(outputStream!!)
                    path.startsWith("/api/connect_peer") -> handleConnectPeer(headers, input, outputStream!!)
                    path.startsWith("/api/disconnect_peer") -> handleDisconnectPeer(outputStream!!)
                    path.startsWith("/api/upload") -> handleUpload(headers, input, outputStream!!)
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

        private fun readBody(input: InputStream, length: Int): String {
            if (length <= 0) return ""
            val buf = ByteArray(length)
            var readTotal = 0
            while (readTotal < length) {
                val r = input.read(buf, readTotal, length - readTotal)
                if (r == -1) break
                readTotal += r
            }
            return String(buf, 0, readTotal, Charsets.UTF_8)
        }

        private fun handleConnectPeer(headers: Map<String, String>, input: InputStream, out: OutputStream) {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            var clientIp = socket.inetAddress?.hostAddress ?: ""
            var clientPort = 52021
            var modelName = "安卓手机"

            if (length > 0) {
                try {
                    val bodyJson = JSONObject(readBody(input, length))
                    val ip = bodyJson.optString("ip", "")
                    if (ip.isNotEmpty() && ip != "127.0.0.1") {
                        clientIp = ip
                    }
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

            val fileName = headers["x-file-name"]?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            } ?: "received_file"

            val targetDirPath = headers["x-target-dir"]?.let {
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
                val boundary = contentType.substringAfter("boundary=").trim().trim('"')
                saveMultipartUpload(input, boundary, contentLength, destDir, fileName, out)
            } else {
                saveRawUpload(input, fileName, contentLength, destDir, out)
            }
        }

        private fun uniqueTarget(destDir: File, fileName: String): File {
            var targetFile = File(destDir, fileName)
            var counter = 1
            while (targetFile.exists()) {
                val dot = fileName.lastIndexOf('.')
                targetFile = if (dot > 0) {
                    val base = fileName.substring(0, dot)
                    val ext = fileName.substring(dot)
                    File(destDir, "${base}_$counter$ext")
                } else {
                    File(destDir, "${fileName}_$counter")
                }
                counter++
            }
            return targetFile
        }

        private fun saveRawUpload(
            input: InputStream,
            fileName: String,
            contentLength: Long,
            destDir: File,
            out: OutputStream
        ) {
            val targetFile = uniqueTarget(destDir, fileName)

            listener?.onFileTransferStarted(fileName, contentLength, false)

            val buffer = ByteArray(128 * 1024)
            var totalRead: Long = 0
            var lastReportTime = System.currentTimeMillis()
            var bytesInInterval: Long = 0

            try {
                FileOutputStream(targetFile).use { outputStream ->
                    while (contentLength <= 0 || totalRead < contentLength) {
                        val toRead = if (contentLength > 0) {
                            Math.min(buffer.size.toLong(), contentLength - totalRead).toInt()
                        } else buffer.size
                        val read = input.read(buffer, 0, toRead)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        totalRead += read
                        bytesInInterval += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastReportTime
                        if (elapsed >= 250) {
                            val speed = if (elapsed > 0) (bytesInInterval * 1000 / elapsed) else 0L
                            listener?.onFileTransferProgress(totalRead, contentLength.coerceAtLeast(totalRead), speed)
                            lastReportTime = now
                            bytesInInterval = 0
                        }
                    }
                    outputStream.flush()
                }

                if (totalRead <= 0) {
                    targetFile.delete()
                    listener?.onFileTransferFailed(fileName, "未收到任何数据")
                    sendResponse(out, 500, "application/json", "{\"status\":\"error\",\"message\":\"empty body\"}")
                    return
                }

                listener?.onFileTransferProgress(totalRead, totalRead, 0L)

                val item = FileItem(
                    id = System.currentTimeMillis().toString(),
                    name = targetFile.name,
                    size = targetFile.length(),
                    filePath = targetFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = true
                )
                listener?.onFileTransferCompleted(item)

                val resp = JSONObject()
                resp.put("status", "ok")
                resp.put("file_name", targetFile.name)
                resp.put("save_path", targetFile.absolutePath)
                resp.put("size", targetFile.length())
                sendResponse(out, 200, "application/json; charset=utf-8", resp.toString())
            } catch (e: Exception) {
                try { targetFile.delete() } catch (_: Exception) {}
                listener?.onFileTransferFailed(fileName, e.localizedMessage ?: "写入文件失败")
                sendResponse(out, 500, "application/json", "{\"status\":\"error\",\"message\":\"write failed\"}")
            }
        }

        /**
         * 流式 multipart 解析：只保留尾部 delimiter 长度-1 的字节用于跨块匹配，
         * 命中 `\r\n--boundary` 即认为文件数据结束，绝不会把 boundary 写进文件。
         */
        private fun saveMultipartUpload(
            input: InputStream,
            boundary: String,
            contentLength: Long,
            destDir: File,
            fallbackName: String,
            out: OutputStream
        ) {
            var fileName = fallbackName

            // ---- 1. 读文件 part 的头（直到空行）----
            val headBuf = ByteArrayOutputStream(2048)
            var st = 0
            var c = input.read()
            while (c != -1) {
                headBuf.write(c)
                st = when {
                    (st == 0 || st == 2) && c == '\r'.code -> st + 1
                    (st == 1 || st == 3) && c == '\n'.code -> st + 1
                    c == '\r'.code -> 1
                    else -> 0
                }
                if (st == 4) break
                c = input.read()
            }
            if (st != 4) {
                sendResponse(out, 400, "application/json", "{\"status\":\"error\",\"message\":\"bad multipart\"}")
                return
            }

            val headText = String(headBuf.toByteArray(), Charsets.UTF_8)
            for (line in headText.split("\r\n")) {
                if (line.contains("filename=\"")) {
                    fileName = line.substringAfter("filename=\"").substringBefore("\"")
                }
            }
            if (fileName.isBlank()) fileName = fallbackName

            val targetFile = uniqueTarget(destDir, fileName)
            listener?.onFileTransferStarted(fileName, contentLength, false)

            val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
            val keep = delimiter.size - 1
            var tail = ByteArray(0)
            val buffer = ByteArray(128 * 1024)

            var totalWritten: Long = 0
            var totalConsumed: Long = 0
            var lastReportTime = System.currentTimeMillis()
            var bytesInInterval: Long = 0
            var finished = false

            try {
                FileOutputStream(targetFile).use { fos ->
                    while (!finished) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        totalConsumed += read

                        val data = if (tail.isEmpty()) buffer.copyOf(read) else {
                            val merged = ByteArray(tail.size + read)
                            System.arraycopy(tail, 0, merged, 0, tail.size)
                            System.arraycopy(buffer, 0, merged, tail.size, read)
                            merged
                        }

                        val idx = indexOf(data, delimiter)
                        if (idx >= 0) {
                            // 命中 delimiter：写 delimiter 之前的所有字节
                            fos.write(data, 0, idx)
                            totalWritten += idx
                            finished = true
                        } else {
                            val safe = data.size - keep
                            if (safe > 0) {
                                fos.write(data, 0, safe)
                                totalWritten += safe
                                bytesInInterval += safe
                                tail = data.copyOfRange(safe, data.size)
                            } else {
                                tail = data
                            }
                        }

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastReportTime
                        if (elapsed >= 250) {
                            val speed = if (elapsed > 0) (bytesInInterval * 1000 / elapsed) else 0L
                            listener?.onFileTransferProgress(totalWritten, contentLength.coerceAtLeast(totalWritten), speed)
                            lastReportTime = now
                            bytesInInterval = 0
                        }
                    }
                    fos.flush()
                }

                // 把剩余请求体读完，避免对端写超时（Connection: close，丢弃即可）
                try {
                    val drain = ByteArray(32 * 1024)
                    var guard = 0
                    while (contentLength > 0 && totalConsumed < contentLength && guard < 20000) {
                        val r = input.read(drain)
                        if (r == -1) break
                        totalConsumed += r
                        guard++
                    }
                } catch (_: Exception) {}

                if (totalWritten <= 0) {
                    targetFile.delete()
                    listener?.onFileTransferFailed(fileName, "未收到任何数据")
                    sendResponse(out, 500, "application/json", "{\"status\":\"error\",\"message\":\"empty body\"}")
                    return
                }

                listener?.onFileTransferProgress(totalWritten, totalWritten, 0L)

                val item = FileItem(
                    id = System.currentTimeMillis().toString(),
                    name = targetFile.name,
                    size = targetFile.length(),
                    filePath = targetFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = true
                )
                listener?.onFileTransferCompleted(item)

                val resp = JSONObject()
                resp.put("status", "ok")
                resp.put("file_name", targetFile.name)
                resp.put("save_path", targetFile.absolutePath)
                resp.put("size", targetFile.length())
                sendResponse(out, 200, "application/json; charset=utf-8", resp.toString())
            } catch (e: Exception) {
                try { targetFile.delete() } catch (_: Exception) {}
                listener?.onFileTransferFailed(fileName, e.localizedMessage ?: "保存失败")
                sendResponse(out, 500, "application/json", "{\"status\":\"error\",\"message\":\"save failed\"}")
            }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            val limit = haystack.size - needle.size
            outer@ for (i in 0..limit) {
                if (haystack[i] != needle[0]) continue
                for (j in 1 until needle.size) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        private fun sendResponse(out: OutputStream, code: Int, contentType: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val statusText = when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                else -> "OK"
            }
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
            } catch (_: Exception) {}
            try {
                outputStream?.close()
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
            clientHandlers.remove(connId)
        }
    }
}
