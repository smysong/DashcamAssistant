package com.dashcam.assistant

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量级 Markdown 渲染（仅覆盖更新日志常用子集，不引入额外依赖/联网拉包）：
 *  - 标题：# / ## / ### / #### / ##### / ######
 *  - 无序列表：- 或 * 开头
 *  - 有序列表：1. 2. ... 开头
 *  - 围栏代码块：``` ... ```
 *  - 行内格式：**粗体**、`行内代码`、[文本](链接)（链接以粗体展示，不跳转）
 * 段落与空行之间自动留白；内容过长时内部滚动（由调用方指定 maxHeightDp）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxHeightDp: Int = 320
) {
    val annotated = remember(markdown, style) { parseMarkdown(markdown, style) }
    Text(
        text = annotated,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeightDp.dp)
            .verticalScroll(rememberScrollState()),
        style = style
    )
}

private fun parseMarkdown(md: String, baseStyle: TextStyle): AnnotatedString {
    val normalized = md.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalized.split("\n")
    val builder = AnnotatedString.Builder()
    var i = 0
    var started = false
    val headingRe = Regex("^#{1,6}\\s.*")
    val bulletRe = Regex("^\\s*[-*]\\s+.*")
    val orderedRe = Regex("^\\s*\\d+\\.\\s+.*")
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    if (code.isNotEmpty()) code.append("\n")
                    code.append(lines[i])
                    i++
                }
                i++ // 跳过结束的 ```
                if (started) appendGap(builder) else started = true
                val start = builder.length
                builder.append(code)
                builder.addStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace),
                    start, builder.length
                )
                builder.append("\n")
            }
            headingRe.matches(line) -> {
                val level = line.takeWhile { it == '#' }.length
                val text = line.substring(level).trim()
                if (started) appendGap(builder) else started = true
                val (size, weight) = when (level) {
                    1 -> 22.sp to FontWeight.Bold
                    2 -> 20.sp to FontWeight.Bold
                    3 -> 18.sp to FontWeight.Bold
                    4 -> 16.sp to FontWeight.SemiBold
                    5 -> 15.sp to FontWeight.SemiBold
                    else -> 14.sp to FontWeight.SemiBold
                }
                val start = builder.length
                builder.append(text)
                builder.addStyle(SpanStyle(fontSize = size, fontWeight = weight), start, builder.length)
                builder.append("\n")
                i++
            }
            bulletRe.matches(line) -> {
                if (started) appendGap(builder) else started = true
                while (i < lines.size && bulletRe.matches(lines[i])) {
                    val item = lines[i].replace(Regex("^\\s*[-*]\\s+"), "")
                    builder.append("•  ")
                    appendInline(builder, item)
                    builder.append("\n")
                    i++
                }
            }
            orderedRe.matches(line) -> {
                if (started) appendGap(builder) else started = true
                var n = 1
                while (i < lines.size && orderedRe.matches(lines[i])) {
                    val item = lines[i].replace(Regex("^\\s*\\d+\\.\\s+"), "")
                    builder.append("${n}.  ")
                    appendInline(builder, item)
                    builder.append("\n")
                    i++
                    n++
                }
            }
            line.isBlank() -> {
                i++
            }
            else -> {
                if (started) appendGap(builder) else started = true
                while (i < lines.size && lines[i].isNotBlank()
                    && !headingRe.matches(lines[i])
                    && !bulletRe.matches(lines[i])
                    && !orderedRe.matches(lines[i])
                    && !lines[i].startsWith("```")
                ) {
                    if (builder.length > 0 && builder.toString().last() != '\n') builder.append(" ")
                    appendInline(builder, lines[i])
                    i++
                }
                builder.append("\n")
            }
        }
    }
    return builder.toAnnotatedString()
}

/** 块与块之间补一个空行（每个块结尾已带 \n，再补一个即空行）。 */
private fun appendGap(builder: AnnotatedString.Builder) {
    if (builder.length > 0) builder.append("\n")
}

/** 行内解析：**粗体**、`代码`、[文本](链接)。 */
private fun appendInline(builder: AnnotatedString.Builder, text: String) {
    val pattern = Regex("(\\*\\*([^*]+?)\\*\\*)|(`([^`]+?)`)|(\\[([^\\]]+?)\\]\\(([^)]+?)\\))")
    var last = 0
    for (m in pattern.findAll(text)) {
        if (m.range.first > last) builder.append(text.substring(last, m.range.first))
        when {
            m.groups[2] != null -> {
                val s = builder.length
                builder.append(m.groupValues[2])
                builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, builder.length)
            }
            m.groups[4] != null -> {
                val s = builder.length
                builder.append(m.groupValues[4])
                builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), s, builder.length)
            }
            m.groups[6] != null -> {
                val s = builder.length
                builder.append(m.groupValues[6])
                builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, builder.length)
            }
        }
        last = m.range.last + 1
    }
    if (last < text.length) builder.append(text.substring(last))
}
