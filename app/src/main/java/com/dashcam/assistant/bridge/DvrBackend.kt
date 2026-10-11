package com.dashcam.assistant.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.io.OutputStream
import java.util.regex.Pattern
import com.dashcam.assistant.AppLog
import com.dashcam.assistant.mediaTimestamp

/**
 * DVR 后端抽象（仅 HTTP 模式）。
 *
 * 设计要点（2026-09-21 重构：移除 amba 模式，统一走 HTTP）：
 * - 列表/下载/缩略图探测 全部基于 DVR 的 Cherokee 目录自动索引（HTTP:80）。
 * - 车机实时预览走 RTSP:554（rtsp://host/live），由原生 Media3 播放；手机网页不提供直播。
 * - HTTP 模式 DVR 无删除端点 → supportsDelete 恒 false。
 * - 车机端播放直接走 DVR 直链（directUrl），不在本机中继整段媒体；设备的 Range 行为须按型号/固件实测。
 *
 * 实车确认目录（Cherokee 自动索引，2026-10-02 用户实测 DVR 直链）：
 *   普通录像：/SD0/NOR/M_video/NOR_YYYYMMDD_HHMMSSA.MP4
 *   紧急录像：/SD0/EVT/M_video/EVT_YYYYMMDD_HHMMSSA.MP4
 *   照片：    /SD0/PHO/M_photo/PHO_YYYYMMDD_HHMMSSA.JPG
 * 注意：三类媒体都落在 M_video/M_photo 目录下，用 NOR/EVT/PHO 一级目录区分，
 * 因此分类不能只看扩展名，必须结合「文件名前缀 + 路径层级」判定（见 matchesType）。
 */
interface DvrBackend {
    /** 当前是否可管理（HTTP=已发现 DVR 即就绪）。 */
    fun isReady(): Boolean
    /** 实时预览地址（rtsp://host/live）。 */
    fun liveUrl(): String
    /** 是否支持删除（HTTP 方案不支持）。 */
    fun supportsDelete(): Boolean
    /** 列出媒体：type ∈ video(普通录像) / event(紧急录像) / photo(照片)。 */
    suspend fun listMedia(type: String): List<MediaItem>
    /** 分页列出媒体：offset 起始下标（从 0 开始），limit 每页数量。后端缓存全量枚举后按页切片。 */
    suspend fun listMediaPaged(type: String, offset: Int, limit: Int): PageResult
    /** 清空指定类型（或所有类型）的枚举缓存，强制下次重新扫描 DVR。 */
    fun invalidateCache(type: String? = null)
    /** 取文件字节（下载/回放中继用）。 */
    suspend fun getFileBytes(path: String, size: Int = 0): ByteArray?
    /** 流式传输媒体文件，不在内存中缓存完整文件；进度总量未知时为 null。 */
    suspend fun copyFileTo(
        path: String,
        output: OutputStream,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Long
    /** DVR 直链（车机端播放/缩略图/照片直连用）。 */
    fun directUrl(path: String): String
    /** DVR 缩略图端点（HTTP 模式不使用，返回 null；缩略图改由客户端动态生成）。 */
    suspend fun getThumb(path: String): ByteArray?
    /** 删除（HTTP 不支持，恒 false）。 */
    suspend fun deleteFile(path: String): Boolean
}

/** 媒体条目（从原 AmbaClient 迁入，作为与 DVR 无关的通用数据模型）。 */
data class MediaItem(
    val path: String,
    val name: String,
    val thumb: String,
    val date: String,
    val duration: Double,
    val size: Long,
    val type: String,
    val locked: Boolean
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("path", path)
        put("name", name)
        put("thumb", thumb.ifBlank { mobileThumbnailUrl(path) })
        put("date", date)
        put("duration", duration)
        put("size", size)
        put("type", type)
        put("locked", locked)
    }
}

/** 分页结果：后端缓存全量枚举、按页切片返回，避免一次性把全部媒体加载到客户端。 */
data class PageResult(val items: List<MediaItem>, val total: Int, val offset: Int, val limit: Int)

private val SUPPORTED_MEDIA_TYPES = setOf("video", "event", "photo")

/**
 * HTTP 后端：HTTP:80 目录索引(列表/下载) + RTSP:554(直播)，不占用 amba 会话，与原厂 APP 永久共存。
 */
class HttpBackend(private val host: String, private val port: Int = 80) : DvrBackend {
    override fun isReady() = host.isNotEmpty()
    override fun liveUrl() = if (host.isBlank()) "" else "rtsp://$host/live"
    override fun supportsDelete() = false
    override fun directUrl(path: String) = if (host.isBlank()) "" else "http://$host:$port${ensureSlash(path)}"

    private val imageExts = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
    private val videoExts = setOf("mp4", "mov", "avi", "mkv", "ts", "3gp", "flv", "m4v")

    // 实车实测根目录（2026-10-02）；后面的条目为兼容其它固件布局的候选。
    private val videoRoots = listOf("/SD0/NOR/M_video/", "/SD0/NOR/M_event/")
    private val eventRoots = listOf("/SD0/EVT/M_video/", "/SD0/EVT/M_event/")
    private val photoRoots = listOf("/SD0/PHO/M_photo/", "/SD0/PHO/M_video/")

    // 枚举结果缓存：DVR 目录递归扫描较慢，分页/刷新共享同一份全量列表，避免每次翻页都重新扫描。
    private val cacheLock = Any()
    private val listCache = mutableMapOf<String, List<MediaItem>>()
    private val listCacheTs = mutableMapOf<String, Long>()
    // 列表缓存时长：DVR 目录为 Cherokee 递归索引，扫描偏慢且受嵌入式服务器并发限制，
    // 在 2.4G WiFi(≈200M) 下网络并非瓶颈，故拉长 TTL 减少重复全量扫描。
    private val LIST_CACHE_MS = 120_000L

    /** 取得（必要时扫描并缓存）某类型的全量媒体列表。 */
    private suspend fun collectAll(type: String): List<MediaItem> {
        require(type in SUPPORTED_MEDIA_TYPES) { "不支持的媒体分类：$type" }
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val cached = listCache[type]
            val ts = listCacheTs[type] ?: 0
            if (cached != null && now - ts < LIST_CACHE_MS) return cached
        }
        val out = mutableListOf<MediaItem>()
        if (host.isEmpty()) return out
        val roots = when (type) {
            "photo" -> photoRoots
            "event" -> eventRoots
            else -> videoRoots
        }
        val seen = mutableSetOf<String>()
        var scannedDirectory = false
        // 按分类根目录扫描：目录语义已知，文件即使无 NOR/EVT/PHO 前缀也收录。
        for (root in roots) {
            if (collect(root, 0, type, out, seen, knownRoot = true)) scannedDirectory = true
        }
        // 已知根为空（机型/固件差异）时回退：先扫 SD 卡根，再退到站点根；
        // 回退扫描不做目录语义假设，仅按「文件名前缀 + 路径层级」严格分类。
        if (out.isEmpty() && collect("/SD0/", 0, type, out, seen, knownRoot = false)) scannedDirectory = true
        if (out.isEmpty() && collect("/", 0, type, out, seen, knownRoot = false)) scannedDirectory = true
        if (!scannedDirectory) throw IOException("无法读取 DVR 媒体目录，请确认 HTTP 目录索引和设备目录配置")
        // 统一按时间倒序（新的在前），保证按日期分栏时同一天连续出现，且分页顺序稳定；
        // 同一秒的多个文件（A/B 后缀）再用文件名降序做稳定次序。
        val result = out.sortedWith(
            compareByDescending<MediaItem> { mediaTimestamp(it.name) }.thenByDescending { it.name }
        )
        // 屏蔽最新一条普通录像：DVR 写入中的 MP4 已经在目录索引里出现，但内容不完整，
        // 回放会失败、缩略图也取不到帧。片段时长在原厂 APP 里可由用户配置（1/2/3/5 分钟不等），
        // 无法用固定的“最近 N 分钟”来判断，因此直接按时间倒序剔除第一条。
        // 仅作用于普通录像：紧急录像（EVT）与照片都是事件触发/手动拍摄，不存在正在写入的当前段。
        val filtered = if (type == "video" && result.isNotEmpty()) {
            AppLog.d("媒体", "屏蔽最新一条普通录像（可能正在录制）：${result.first().name}")
            result.drop(1)
        } else {
            result
        }
        synchronized(cacheLock) {
            listCache[type] = filtered
            listCacheTs[type] = now
        }
        return filtered
    }

    override fun invalidateCache(type: String?) {
        synchronized(cacheLock) {
            if (type.isNullOrEmpty()) { listCache.clear(); listCacheTs.clear() }
            else { listCache.remove(type); listCacheTs.remove(type) }
        }
    }

    override suspend fun listMedia(type: String): List<MediaItem> = withContext(Dispatchers.IO) { collectAll(type) }

    override suspend fun listMediaPaged(type: String, offset: Int, limit: Int): PageResult = withContext(Dispatchers.IO) {
        val all = collectAll(type)
        val from = maxOf(0, offset)
        val limit0 = maxOf(1, limit)
        val to = minOf(all.size, from + limit0)
        val slice = if (from >= all.size) emptyList() else all.subList(from, to)
        PageResult(slice, all.size, from, limit0)
    }

    override suspend fun getThumb(path: String): ByteArray? = null

    override suspend fun deleteFile(path: String) = false

    override suspend fun getFileBytes(path: String, size: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (host.isEmpty()) null else httpGetBytes(ensureSlash(path))
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun copyFileTo(
        path: String,
        output: OutputStream,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long?) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        if (host.isEmpty()) throw IOException("DVR 未连接")
        val connection = URL(directUrl(path)).openConnection() as HttpURLConnection
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true
        ) { cause ->
            if (cause is CancellationException) connection.disconnect()
        }
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 60000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("DVR 返回 HTTP $code：$path")
            val length = connection.contentLengthLong.takeIf { it >= 0 }
            val copied = connection.inputStream.use { input ->
                copyMediaStream(input, output, length, onProgress)
            }
            if (length != null && copied != length) {
                throw IOException("媒体传输不完整：收到 $copied 字节，预期 $length 字节")
            }
            if (copied == 0L) throw IOException("DVR 返回空媒体文件")
            copied
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cancellationHandle?.dispose()
            connection.disconnect()
        }
    }

    private fun ensureSlash(p: String) = if (p.startsWith("/")) p else "/$p"

    private suspend fun collect(
        dir: String,
        depth: Int,
        type: String,
        out: MutableList<MediaItem>,
        visited: MutableSet<String>,
        knownRoot: Boolean
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (depth > 8 || !visited.add(dir)) return false
        val html = httpGetText(ensureSlash(dir)) ?: return false
        // 索引页里每个条目的文件大小（Cherokee/Apache/Nginx 目录索引都带大小列），
        // 用于让列表与下载前能显示文件大小；解析不到时降级为 0，不影响其它功能。
        val sizes = parseIndexSizes(html)
        AppLog.d("媒体", "索引 $dir：解析到 ${sizes.size} 个文件大小")
        for (link in parseLinks(html)) {
            currentCoroutineContext().ensureActive()
            val full = resolve(dir, link)
            if (full.isEmpty()) continue
            if (link.endsWith("/")) collect(full, depth + 1, type, out, visited, knownRoot)
            else if (matchesType(full, type, knownRoot)) {
                out.add(toItem(full, sizes[link.substringAfterLast('/')] ?: 0L))
            }
        }
        return true
    }

    private fun resolve(dir: String, link: String): String {
        if (link.startsWith("/")) return link
        val base = if (dir.endsWith("/")) dir else "$dir/"
        return base + link
    }

    /**
     * 判定文件是否属于指定分类。
     * 三类媒体同为 MP4/JPG，仅靠扩展名无法区分，故按优先级判定：
     * 1) 文件名前缀 NOR_/EVT_/PHO_ → 必须等于目标分类；
     * 2) 路径层级含 /NOR/ /EVT/ /PHO/ → 必须等于目标分类；
     * 3) 无前缀也无层级线索 → 仅在已知分类根目录（knownRoot）下收录。
     */
    private fun matchesType(full: String, type: String, knownRoot: Boolean): Boolean {
        val name = full.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val extOk = if (type == "photo") ext in imageExts else ext in videoExts
        if (!extOk) return false
        namePrefixCategory(name)?.let { return it == type }
        pathCategory(full)?.let { return it == type }
        return knownRoot
    }

    /** 文件名前缀 → 分类（NOR_ / EVT_ / PHO_，兼容 - 与 . 分隔）。 */
    private fun namePrefixCategory(name: String): String? {
        val up = name.uppercase()
        val sep = up.indexOfFirst { it == '_' || it == '-' || it == '.' }
        val head = if (sep > 0) up.substring(0, sep) else ""
        return when (head) {
            "NOR" -> "video"
            "EVT" -> "event"
            "PHO" -> "photo"
            else -> null
        }
    }

    /** 路径层级 → 分类（/SD0/NOR/… /SD0/EVT/… /SD0/PHO/…）。 */
    private fun pathCategory(full: String): String? {
        val up = full.uppercase()
        return when {
            up.contains("/NOR/") -> "video"
            up.contains("/EVT/") -> "event"
            up.contains("/PHO/") -> "photo"
            else -> null
        }
    }

    private fun toItem(full: String, size: Long = 0L): MediaItem {
        val name = full.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val t = if (ext in imageExts) "photo" else "video"
        // date 字段存放“友好日期时间”（由文件名解析），前端直接展示；解析失败则留空。
        return MediaItem(full, name, "", formatDvrDateTime(name), 0.0, size, t, false)
    }

    // ---- 目录索引里的文件大小解析 ----
    // 索引页每行形如 …<a href="NOR_….MP4">…</a>…</td><td>1234567</td>…
    // 或 Apache/Nginx 风格 <td align="right">1.2M</td>。兼容“裸字节数”与“带单位”两种写法。
    private val INDEX_ROW_RE = Regex("""<tr[^>]*>([\s\S]*?)</tr>|<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
    private val INDEX_HREF_RE = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    // 两级匹配：先认带 B 的单位（MB/MiB），再认 Apache/Nginx 那种省略 B 的写法（1.5G、153.2M）；
    // 第二级要求单位右侧必须是标签/空白/行尾，避免把 "1080P" 之类的文本误当成 1PB。
    private val INDEX_HUMAN_SIZE_RE = Regex("""(\d+(?:[.,]\d+)?)\s*([KMGTP]i?B)(?![\w])""", RegexOption.IGNORE_CASE)
    private val INDEX_SHORT_SIZE_RE = Regex("""(\d+(?:[.,]\d+)?)\s*([KMGTP])(?=\s*<|\s|$)""", RegexOption.IGNORE_CASE)
    private val INDEX_RAW_SIZE_RE = Regex("""<td[^>]*>\s*(\d{3,})\s*</td>""", RegexOption.IGNORE_CASE)
    // DVR（Cherokee/Amba 模板）把数值与单位拆成两个 span，且小文件会把整段塞进 unit：
    //   <span class="size">280</span><span class="unit">M</span>
    //   <span class="size"></span><span class="unit"> 20M</span>
    // 因此必须成对取两个 span 的文本再拼接，缺一个都会漏。
    private val INDEX_SPAN_SIZE_RE = Regex(
        """<span[^>]*class\s*=\s*["'][^"']*\bsize\b[^"']*["'][^>]*>([^<]*)</span>\s*""" +
            """<span[^>]*class\s*=\s*["'][^"']*\bunit\b[^"']*["'][^>]*>([^<]*)</span>""",
        RegexOption.IGNORE_CASE
    )
    // 拼接后的“值+单位”串，如 280M / 20M / 1.5G / 153.2MiB（B 可省略）
    private val INDEX_SIZE_TOKEN_RE = Regex("""(\d+(?:[.,]\d+)?)\s*([KMGTP]i?B?)(?![\w])""", RegexOption.IGNORE_CASE)
    // 合理性上限：行车记录仪单文件不可能超过 32GB，超过则判定为解析异常（如误把 1080P 当 1080PB），
    // 宁可不显示也不能显示荒谬数值。
    private val MAX_PLAUSIBLE_SIZE = 32L * 1024 * 1024 * 1024

    /** 文件名 → 字节数；索引页未提供大小信息时返回空表（媒体 size 保持 0）。 */
    private fun parseIndexSizes(html: String): Map<String, Long> {
        if (html.length > 2 * 1024 * 1024) return emptyMap()
        val result = mutableMapOf<String, Long>()
        INDEX_ROW_RE.findAll(html).forEach { rowMatch ->
            val row = rowMatch.groupValues[1].ifEmpty { rowMatch.groupValues[2] }
            val href = INDEX_HREF_RE.find(row)?.groupValues?.get(1) ?: return@forEach
            val name = href.substringAfterLast('/').substringBefore('?')
            if (name.isEmpty()) return@forEach
            val bytes = parseSpanSize(row)
                ?: INDEX_HUMAN_SIZE_RE.find(row)?.let { parseHumanSize(it.groupValues[1], it.groupValues[2]) }
                ?: INDEX_SHORT_SIZE_RE.find(row)?.let { parseHumanSize(it.groupValues[1], it.groupValues[2]) }
                ?: INDEX_RAW_SIZE_RE.find(row)?.groupValues?.get(1)?.toLongOrNull()
            if (bytes != null && bytes > 0 && bytes <= MAX_PLAUSIBLE_SIZE) result[name] = bytes
        }
        return result
    }

    /** DVR 索引的 `<span class="size">…</span><span class="unit">…</span>` 成对解析。 */
    private fun parseSpanSize(row: String): Long? {
        val m = INDEX_SPAN_SIZE_RE.find(row) ?: return null
        val token = (m.groupValues[1] + m.groupValues[2]).replace("&nbsp;", " ").trim()
        if (token.isEmpty()) return null
        val t = INDEX_SIZE_TOKEN_RE.find(token)
            ?: return token.toLongOrNull()?.takeIf { it > 0 } // 少数固件直接给裸字节数
        return parseHumanSize(t.groupValues[1], t.groupValues[2])
    }

    private fun parseHumanSize(value: String, unit: String): Long? {
        val amount = value.replace(',', '.').toDoubleOrNull() ?: return null
        // 去掉可能的 i（MiB），并把省略 B 的短写法（K/M/G/T/P）补全为标准单位名
        var normalized = unit.replace("i", "", ignoreCase = true).uppercase()
        if (normalized.length == 1 && normalized != "B") normalized += "B"
        val factor = when (normalized) {
            "B" -> 1L
            "KB" -> 1024L
            "MB" -> 1024L * 1024
            "GB" -> 1024L * 1024 * 1024
            "TB" -> 1024L * 1024 * 1024 * 1024
            "PB" -> 1024L * 1024 * 1024 * 1024 * 1024
            else -> return null
        }
        return (amount * factor).toLong()
    }

    private fun parseLinks(html: String): List<String> {
        val list = mutableListOf<String>()
        val m = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        while (m.find()) {
            val h = m.group(1) ?: continue
            if (h.startsWith("?") || h.startsWith("#") || h == "../" || h == "./" || h.startsWith("//")) continue
            list.add(h)
        }
        return list
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun httpGetText(path: String): String? {
        val conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true
        ) { cause ->
            if (cause is CancellationException) conn.disconnect()
        }
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 15000; conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code in 500..599) throw IOException("DVR 返回 HTTP $code：$path")
            if (code != 200) return null
            val ct = conn.contentType ?: ""
            if (!ct.contains("text/html")) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cancellationHandle?.dispose()
            conn.disconnect()
        }
    }

    private fun httpGetBytes(path: String): ByteArray? {
        val conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 60000; conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("DVR 返回 HTTP $code：$path")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

// ===================== 文件名 → 友好日期时间 =====================
// DVR 文件名形如 NOR_20260921_041624A.MP4 / EVT_20260921_041624.MP4 / PHO_20260921_041624A.JPG，
// 其中 8 位为日期 YYYYMMDD、6 位为时间 HHMMSS（可能带前缀/后缀字母）。

private val DVR_DT_RE = Regex("""(\d{4})(\d{2})(\d{2})[_.\s-]*(\d{2})(\d{2})(\d{2})""")
private val DVR_DATE_RE = Regex("""\b(\d{4})(\d{2})(\d{2})\b""")
private val DVR_TIME_RE = Regex("""\b(\d{2})(\d{2})(\d{2})\b""")
private val WEEK_CN = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

/**
 * 解析 DVR 文件名中的日期时间，返回友好展示串（如「2026-09-21 周一 04:16:24」）。
 * 解析失败（文件名不含合法日期时间）时原样返回文件名，确保信息不丢失。
 */
private fun formatDvrDateTime(name: String): String {
    val base = name.substringBeforeLast('.', name)
    var y: Int? = null; var mo: Int? = null; var d: Int? = null
    var hh: Int? = null; var mm: Int? = null; var ss: Int? = null
    val m = DVR_DT_RE.find(base)
    if (m != null) {
        val g = m.groupValues
        y = g[1].toIntOrNull(); mo = g[2].toIntOrNull(); d = g[3].toIntOrNull()
        hh = g[4].toIntOrNull(); mm = g[5].toIntOrNull(); ss = g[6].toIntOrNull()
    } else {
        val dm = DVR_DATE_RE.find(base); val tm = DVR_TIME_RE.find(base)
        if (dm != null && tm != null) {
            val dg = dm.groupValues; val tg = tm.groupValues
            y = dg[1].toIntOrNull(); mo = dg[2].toIntOrNull(); d = dg[3].toIntOrNull()
            hh = tg[1].toIntOrNull(); mm = tg[2].toIntOrNull(); ss = tg[3].toIntOrNull()
        }
    }
    if (y == null || mo == null || d == null || hh == null || mm == null || ss == null) return name
    if (mo !in 1..12 || d !in 1..31 || hh !in 0..23 || mm !in 0..59 || ss !in 0..59) return name
    val cal = java.util.Calendar.getInstance()
    cal.set(y, mo - 1, d)
    val wk = WEEK_CN.getOrElse(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1) { "" }
    return "%04d-%02d-%02d %s %02d:%02d:%02d".format(y, mo, d, wk, hh, mm, ss)
}
