package com.dashcam.assistant.bridge

import android.content.Context
import com.dashcam.assistant.AppLog
import com.dashcam.assistant.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * 车机端嵌入式 HTTP 桥（从 bridge-server.js 平移）。
 *
 * 手机扫码(本服务 URL)后在浏览器里即可 浏览 / 回放 / 下载录像照片；直播仅由车机原生页面播放。
 * 内部通过 [DvrBackend](HTTP) 直连 DVR。
 *
 * 2026-09-21 重构：
 * - 移除 amba / SessionGate；仅 HTTP 后端。
 * - /api/play、/api/file 改为**流式 Range 代理**（分块传输 + 支持 206 Partial Content），
 *   不再整文件载入内存 —— 支持播放器缓冲/拖动并降低大文件访问内存占用。
 * - 手机桥接只提供媒体浏览、回放、照片和下载；车机直播走原生 Media3 RTSP。
 */
class HttpServer(
    private val context: Context,
    private val port: Int = 8080
) : BridgeController {
    private val access = BridgeAccess()
    @Volatile var discoveredHost: String? = null
    var onReconnect: (() -> Unit)? = null
    /** 当前激活的 DVR 后端（HTTP），由 BridgeService 注入。 */
    @Volatile var backend: DvrBackend? = null

    private val mobileThumbnails by lazy { MobileThumbnailService(context) { backend } }
    @Volatile private var serverSocket: ServerSocket? = null
    private val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var stopped = false
    val running: Boolean get() = serverSocket != null && !serverSocket!!.isClosed

    /** 分类 type → 中文标签（用于手机操作日志）。 */
    private val TAB_LABEL = mapOf("video" to "普通录像", "event" to "紧急录像", "photo" to "照片")

    fun start() {
        if (running || stopped) return
        thread(name = "HttpServer") {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                if (stopped) {
                    socket.close()
                    return@thread
                }
                while (!socket.isClosed) {
                    val client = socket.accept()
                    clients.add(client)
                    val handler = thread(name = "Http-${client.remoteSocketAddress}", start = false) {
                        try {
                            handle(client)
                        } catch (e: Exception) {
                            AppLog.w("Http", "客户端请求失败：${e.message}")
                        } finally {
                            clients.remove(client)
                            threads.remove(Thread.currentThread())
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                    threads.add(handler)
                    handler.start()
                }
            } catch (e: SocketException) {
                // 正常关闭
            }
        }
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            clients.clear()
        }
        synchronized(threads) {
            threads.forEach { it.interrupt() }
            threads.clear()
        }
    }

    fun pairingUrl(bridgeUrl: String): String = access.pairingUrl(bridgeUrl)

    private fun handle(client: Socket) {
        client.soTimeout = 60000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
        val out = DataOutputStream(client.getOutputStream())
        val requestLine = reader.readLine() ?: run { client.close(); return }
        val parts = requestLine.split(" ")
        if (parts.size < 2) { client.close(); return }
        val method = parts[0]
        val (path, query) = parseUrl(parts[1])

        val headers = mutableMapOf<String, String>()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!
            if (l.isEmpty()) break
            val idx = l.indexOf(':')
            if (idx > 0) headers[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
        }
        val hasSession = access.hasSessionCookie(headers["cookie"])
        if (path == "/" || path == "/index.html") {
            when {
                hasSession -> serveAsset(out, "index.html", "text/html; charset=utf-8")
                access.hasPairingToken(query["token"]) -> servePairingRedirect(out)
                else -> serveJson(out, JSONObject().put("ok", false).put("error", "扫码配对凭据无效或已失效").toString(), 403)
            }
            return
        }
        if (!hasSession) {
            serveJson(out, JSONObject().put("ok", false).put("error", "未授权；请重新扫描车机二维码").toString(), 403)
            return
        }

        // 手机端操作记日志（心跳与高频 Range 子请求跳过，避免冲掉缓冲）
        val op = phoneOpLabel(path, method, query, headers["range"])
        if (op != null) AppLog.i("手机", op)
        try {
            when {
                path == "/api/log" && method == "GET" -> serveJson(out, logJson())
                path == "/api/log" && method == "POST" -> { clearLog(); serveJson(out, JSONObject().put("ok", true).toString()) }
                path == "/api/log/download" -> serveText(out, AppLog.exportText(), "dashcam-assistant.log")
                path == "/api/status" -> serveJson(out, statusJson())
                path == "/api/list" -> {
                    val type = query["type"] ?: "video"
                    val offset = query["offset"]?.toIntOrNull() ?: 0
                    val limit = query["limit"]?.toIntOrNull() ?: 10
                    if (query["refresh"] == "1") backend?.invalidateCache(type)
                    serveJson(out, listJson(type, offset, limit))
                }
                path == "/api/play" -> serveProxy(out, query["path"] ?: "", null, headers["range"])
                path == "/api/file" -> serveProxy(out, query["path"] ?: "", query["path"]?.substringAfterLast('/'), headers["range"])
                path == "/api/thumb" -> {
                    val mediaPath = query["path"].orEmpty()
                    if (mediaPath.isBlank()) {
                        serveJson(out, JSONObject().put("ok", false).put("error", "missing path").toString(), 400)
                    } else {
                        serveThumbnail(out, mediaPath)
                    }
                }
                path == "/api/delete" && method == "POST" -> serveJson(out, JSONObject().put("ok", false).put("error", "HTTP 模式不支持删除").toString(), 405)
                else -> serveJson(out, JSONObject().put("ok", false).put("error", "not found").toString(), 404)
            }
        } catch (e: Exception) {
            AppLog.e("Http", "请求处理异常 $method $path", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun parseUrl(u: String): Pair<String, Map<String, String>> {
        val qIdx = u.indexOf('?')
        val path = if (qIdx >= 0) u.substring(0, qIdx) else u
        val query = if (qIdx >= 0) u.substring(qIdx + 1) else ""
        val map = query.split("&").filter { it.isNotEmpty() }.associate {
            val eq = it.indexOf('=')
            if (eq >= 0) decode(it.substring(0, eq)) to decode(it.substring(eq + 1))
            else decode(it) to ""
        }
        return path to map
    }

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun servePairingRedirect(out: DataOutputStream) {
        out.writeBytes(
            "HTTP/1.1 303 See Other\r\n" +
                "Location: /\r\n" +
                "Set-Cookie: ${access.sessionCookieHeader()}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        )
        out.flush()
    }

    private fun serveAsset(out: DataOutputStream, asset: String, contentType: String) {
        try {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            out.writeBytes("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            serveJson(out, JSONObject().put("ok", false).put("error", "asset not found: $asset").toString(), 404)
        }
    }

    private fun serveJson(out: DataOutputStream, json: String, code: Int = 200) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Error"
        }
        out.writeBytes("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
        out.write(bytes)
        out.flush()
    }

    /** 纯文本响应（用于日志导出下载）。 */
    private fun serveText(out: DataOutputStream, text: String, fileName: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        out.writeBytes("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Disposition: attachment; filename=\"$fileName\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        out.write(bytes)
        out.flush()
    }

    /** 把手机端 HTTP 请求映射为可读的"操作"日志；返回 null 表示不记（心跳/高频）。 */
    private fun phoneOpLabel(path: String, method: String, query: Map<String, String>, range: String?): String? {
        return when {
            path == "/" || path == "/index.html" -> "打开助手网页"
            path == "/api/status" -> null                      // 每 5s 心跳，不记
            path == "/api/log" || path == "/api/log/download" -> null  // 开发者模式自身
            path == "/api/list" -> "切换分类/刷新：${TAB_LABEL[query["type"]] ?: query["type"] ?: "全部"}"
            path == "/api/thumb" -> "读取媒体缩略图：${query["path"] ?: ""}"
            path == "/api/play" -> if (range.isNullOrEmpty()) "打开媒体：${query["path"] ?: ""}" else null  // 仅记首次打开，拖动/缓冲(Range)跳过
            path == "/api/file" -> if (range.isNullOrEmpty()) "下载：${query["path"] ?: ""}" else null
            else -> "$method $path"                            // 其它控制类请求
        }
    }

    /**
     * 流式 Range 代理（request #3）：拦截前端 <video> 的 Range 请求，向 DVR 发起普通 GET，
     * 边接收边转发给前端，不落盘、内存占用可控（固定 16KB 缓冲块）。
     * - 客户端带 Range 时转发上游；仅有效 206 带 Content-Range 时按分段响应返回。
     *   DVR 忽略 Range 并返回 200 时按完整分块响应返回，不伪造 Range 能力。
     * - 客户端不带 Range（整片请求）→ 上游有 Content-Length 时直传（下载管理器可显示总大小），
     *   长度未知才退回 HTTP chunked 分块传输。
     * Cherokee 把视频/照片返回 application/octet-stream 会导致 <video> 拒绝播放，这里按扩展名修正 MIME。
     */
    private fun serveProxy(out: DataOutputStream, path: String, attachmentName: String?, clientRange: String?) {
        if (path.isEmpty()) { serveJson(out, JSONObject().put("ok", false).put("error", "missing path").toString(), 400); return }
        val b = backend
        if (b == null) { serveJson(out, JSONObject().put("ok", false).put("error", "未连接 DVR").toString(), 500); return }
        val url = b.directUrl(path)
        var responseStarted = false
        var conn: HttpURLConnection? = null
        try {
            val upstream = URL(url).openConnection() as HttpURLConnection
            conn = upstream
            upstream.connectTimeout = 8000; upstream.readTimeout = 60000; upstream.requestMethod = "GET"
            if (!clientRange.isNullOrEmpty()) upstream.setRequestProperty("Range", clientRange)
            val code = upstream.responseCode
            val contentLength = upstream.contentLengthLong.takeIf { it >= 0 }
            val proxyPlan = mediaProxyPlan(
                code,
                upstream.getHeaderField("Content-Range"),
                upstream.getHeaderField("Accept-Ranges"),
                contentLength
            )
            if (proxyPlan is MediaProxyPlan.UpstreamFailure) {
                val message = if (code == 206) {
                    "DVR 的分段响应缺少有效的 Content-Range"
                } else {
                    "DVR 返回 HTTP $code"
                }
                serveJson(out, JSONObject().put("ok", false).put("error", message).toString(), 502)
                return
            }
            val ct = guessMediaContentType(path, upstream.contentType)
            val body = if (proxyPlan is MediaProxyPlan.RangeNotSatisfiable) {
                upstream.errorStream ?: ByteArrayInputStream(byteArrayOf())
            } else {
                upstream.inputStream
            }
            responseStarted = true
            writeMediaProxyResponse(out, proxyPlan, ct, contentLength, attachmentName, body)
        } catch (e: Exception) {
            if (isDownstreamClientDisconnect(e)) {
                AppLog.i("Http", "媒体客户端已断开，停止代理传输：$path")
            } else {
                AppLog.e("Http", "DVR 媒体代理失败：$path", e)
            }
            if (!responseStarted) {
                try { serveJson(out, JSONObject().put("ok", false).put("error", "DVR 媒体读取失败：${e.message}").toString(), 502) } catch (_: Exception) {}
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun serveThumbnail(out: DataOutputStream, path: String) {
        val file = try {
            mobileThumbnails.get(path)
        } catch (e: Exception) {
            AppLog.w("缩略图", "手机缩略图生成失败：$path，${e.message}")
            serveJson(
                out,
                JSONObject().put("ok", false).put("error", e.message ?: "缩略图生成失败").toString(),
                502
            )
            return
        }
        try {
            out.writeBytes(
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${file.length()}\r\n" +
                    "Cache-Control: private, max-age=300\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n"
            )
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
            out.flush()
        } catch (e: Exception) {
            if (isDownstreamClientDisconnect(e)) {
                AppLog.i("Http", "缩略图客户端已断开：$path")
            } else {
                AppLog.e("Http", "手机缩略图响应失败：$path", e)
            }
        }
    }

    // ---- BridgeController 实现 ----
    override fun statusJson(): String = runBlocking {
        try {
            val b = backend
            val ready = b?.isReady() ?: false
            JSONObject().apply {
                put("ok", ready)
                put("scheme", "HTTP")
                put("supportsDelete", false)
                put("bridgeUrl", pairingUrl("http://${resolveBridgeIp(context)}:8080/"))
                put("dvr", discoveredHost ?: "")
                put("discovered", discoveredHost != null)
                // 版本号由 server_build.py 每次构建自增（versionName），供手机网页页脚显示
                put("version", BuildConfig.VERSION_NAME)
            }.toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    override fun listJson(type: String, offset: Int, limit: Int): String = runBlocking {
        val b = backend ?: return@runBlocking JSONObject().put("ok", false).put("error", "未连接 DVR").toString()
        if (!b.isReady()) return@runBlocking JSONObject().put("ok", false).put("error", "未连接 DVR").toString()
        try {
            // A：先读本地列表磁盘缓存秒回（首屏即时渲染），无缓存再走 listMediaPaged 全量扫描并落盘；
            // 与车机原生 MediaBrowser.loadPage 的两段式加载对齐。
            val page = b.getCachedListPage(type, offset, limit) ?: b.listMediaPaged(type, offset, limit)
            JSONObject().apply {
                put("ok", true)
                put("type", type)
                put("total", page.total)
                put("offset", page.offset)
                put("limit", page.limit)
                put("hasMore", page.offset + page.limit < page.total)
                put("count", page.items.size)
                put("items", JSONArray().apply { page.items.forEach { put(it.toJson()) } })
            }.toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    override fun getThumb(path: String): ByteArray? = null

    override fun getFile(path: String, size: Int): ByteArray? = runBlocking {
        backend?.getFileBytes(path, size)
    }

    override fun deleteFile(path: String): Boolean = false

    override fun logJson(): String =
        JSONObject().apply {
            put("ok", true)
            put("lines", JSONArray().apply { AppLog.snapshot().forEach { put(it) } })
        }.toString()

    override fun clearLog() = AppLog.clear()

    override fun reconnect() {
        onReconnect?.invoke()
    }
}
