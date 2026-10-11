package com.dashcam.assistant

import androidx.compose.runtime.Immutable
import com.dashcam.assistant.bridge.MediaItem

/**
 * 媒体按日期分组（车机媒体库分栏）。
 *
 * DVR 文件名形如 NOR_20260930_152022A.MP4 / EVT_20260930_155124A.MP4 / PHO_20260921_093154A.JPG，
 * 其中 8 位为日期 YYYYMMDD、6 位为时间 HHMMSS。分组与排序都从文件名解析，不依赖文件元数据
 * （Cherokee 目录索引不带时间戳）。
 */

/** 无法解析日期时的分组 key（会统一归入「未标注日期」）。 */
internal const val UNDATED_GROUP_KEY = "undated"

private val MEDIA_DT_RE = Regex("""(\d{4})(\d{2})(\d{2})[_.\s-]*(\d{2})(\d{2})(\d{2})""")
private val WEEK_CN = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

/** 按日期分好组的一组媒体：key 用于列表稳定性，label 是分栏标题（如「2026年10月01日 周四」）。 */
@Immutable
internal data class MediaDateGroup(
    val key: String,
    val label: String,
    val items: List<MediaItem>
)

private data class ParsedStamp(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)

private fun parseStamp(name: String): ParsedStamp? {
    val m = MEDIA_DT_RE.find(name.substringBeforeLast('.', name)) ?: return null
    val g = m.groupValues
    val y = g[1].toIntOrNull() ?: return null
    val mo = g[2].toIntOrNull() ?: return null
    val d = g[3].toIntOrNull() ?: return null
    val hh = g[4].toIntOrNull() ?: return null
    val mm = g[5].toIntOrNull() ?: return null
    val ss = g[6].toIntOrNull() ?: return null
    if (mo !in 1..12 || d !in 1..31 || hh !in 0..23 || mm !in 0..59 || ss !in 0..59) return null
    return ParsedStamp(y, mo, d, hh, mm, ss)
}

/** 排序用的时间戳（yyyyMMddHHmmss），解析失败返回 Long.MIN_VALUE（排在最末）。 */
internal fun mediaTimestamp(name: String): Long {
    val s = parseStamp(name) ?: return Long.MIN_VALUE
    return s.year.toLong() * 10_000_000_000L +
        s.month * 100_000_000L +
        s.day * 1_000_000L +
        s.hour * 10_000L +
        s.minute * 100L +
        s.second
}

/** 分组 key（yyyy-MM-dd）；解析失败归入 UNDATED_GROUP_KEY。 */
internal fun mediaDateKey(name: String): String {
    val s = parseStamp(name) ?: return UNDATED_GROUP_KEY
    return "%04d-%02d-%02d".format(s.year, s.month, s.day)
}

/** 分栏标题，如「2026年10月01日 周四」；无法解析时为「未标注日期」。 */
internal fun mediaDateLabel(key: String): String {
    if (key == UNDATED_GROUP_KEY) return "未标注日期"
    val parts = key.split('-')
    if (parts.size != 3) return key
    val y = parts[0].toIntOrNull() ?: return key
    val mo = parts[1].toIntOrNull() ?: return key
    val d = parts[2].toIntOrNull() ?: return key
    val cal = java.util.Calendar.getInstance().apply { set(y, mo - 1, d) }
    val week = WEEK_CN.getOrElse(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1) { "" }
    return "%04d年%02d月%02d日 %s".format(y, mo, d, week)
}

/**
 * 把媒体列表按日期切成连续的分组：**保持传入顺序**，遇到不同日期就开新组。
 * 因此调用方需先按时间倒序排好（见 DvrBackend 的排序），否则同一日期会出现在多个分组里。
 */
internal fun groupMediaByDate(items: List<MediaItem>): List<MediaDateGroup> {
    if (items.isEmpty()) return emptyList()
    val groups = mutableListOf<MediaDateGroup>()
    val buffer = mutableListOf<MediaItem>()
    var current = mediaDateKey(items.first().name)
    for (item in items) {
        val key = mediaDateKey(item.name)
        if (key != current) {
            groups += MediaDateGroup(current, mediaDateLabel(current), buffer.toList())
            buffer.clear()
            current = key
        }
        buffer += item
    }
    if (buffer.isNotEmpty()) groups += MediaDateGroup(current, mediaDateLabel(current), buffer.toList())
    return groups
}
