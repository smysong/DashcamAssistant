package com.dashcam.assistant.bridge

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream

internal suspend fun copyMediaStream(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long?,
    onProgress: suspend (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
): Long {
    val context = currentCoroutineContext()
    val buffer = ByteArray(64 * 1024)
    var copiedBytes = 0L
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        copiedBytes += count
        onProgress(copiedBytes, totalBytes)
    }
    output.flush()
    return copiedBytes
}

internal fun isValidContentRange(value: String?, contentLength: Long? = null): Boolean {
    val match = CONTENT_RANGE_REGEX.matchEntire(value?.trim().orEmpty()) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    val rangeLength = end - start + 1
    return end >= start &&
        rangeLength > 0 &&
        (total == null || (total > 0 && end < total)) &&
        (contentLength == null || contentLength < 0 || contentLength == rangeLength)
}

internal fun validUnsatisfiedContentRange(value: String?): String? {
    val candidate = value?.trim() ?: return null
    return candidate.takeIf { UNSATISFIED_CONTENT_RANGE_REGEX.matches(it) }
}

internal sealed interface MediaProxyPlan {
    data class Partial(val contentRange: String) : MediaProxyPlan
    data class Full(val advertisesByteRanges: Boolean) : MediaProxyPlan
    data class RangeNotSatisfiable(val contentRange: String?) : MediaProxyPlan
    data class UpstreamFailure(val statusCode: Int) : MediaProxyPlan
}

internal fun mediaProxyPlan(
    statusCode: Int,
    contentRange: String?,
    acceptRanges: String?,
    contentLength: Long? = null
): MediaProxyPlan = when (statusCode) {
    200 -> MediaProxyPlan.Full(acceptRanges?.trim()?.equals("bytes", ignoreCase = true) == true)
    206 -> if (isValidContentRange(contentRange, contentLength)) {
        MediaProxyPlan.Partial(contentRange!!.trim())
    } else {
        MediaProxyPlan.UpstreamFailure(statusCode)
    }
    416 -> MediaProxyPlan.RangeNotSatisfiable(validUnsatisfiedContentRange(contentRange))
    else -> MediaProxyPlan.UpstreamFailure(statusCode)
}

internal fun rangeNotSatisfiableHeaders(contentRange: String?, contentLength: Int): String =
    buildString {
        append("HTTP/1.1 416 Range Not Satisfiable\r\n")
        append("Content-Type: application/json; charset=utf-8\r\n")
        validUnsatisfiedContentRange(contentRange)?.let { append("Content-Range: $it\r\n") }
        append("Content-Length: $contentLength\r\n")
        append("Cache-Control: no-store\r\n")
        append("Connection: close\r\n\r\n")
    }

private val CONTENT_RANGE_REGEX = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
private val UNSATISFIED_CONTENT_RANGE_REGEX = Regex("""bytes \*/\d+""", RegexOption.IGNORE_CASE)
