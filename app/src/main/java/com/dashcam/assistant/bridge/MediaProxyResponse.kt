package com.dashcam.assistant.bridge

import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.nio.charset.StandardCharsets

internal fun isDownstreamClientDisconnect(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is SocketException || current is IOException) {
            val message = current.message.orEmpty().lowercase()
            if (
                "broken pipe" in message ||
                "connection reset" in message ||
                "socket closed" in message ||
                "connection abort" in message
            ) return true
        }
        current = current.cause
    }
    return false
}

internal fun writeMediaProxyResponse(
    output: DataOutputStream,
    plan: MediaProxyPlan,
    contentType: String,
    contentLength: Long?,
    attachmentName: String?,
    body: InputStream
) {
    when (plan) {
        is MediaProxyPlan.Partial -> {
            output.writeBytes(
                buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Content-Range: ${plan.contentRange}\r\n")
                    contentLength?.takeIf { it >= 0 }?.let { append("Content-Length: $it\r\n") }
                    append("Accept-Ranges: bytes\r\n")
                    attachmentName?.let { append("Content-Disposition: attachment; filename=\"$it\"\r\n") }
                    append("Connection: close\r\n\r\n")
                }
            )
            writeBody(output, body, chunked = false)
        }
        is MediaProxyPlan.Full -> {
            // 上游给出了 Content-Length 时必须原样下发（直传，不分块），
            // 否则浏览器下载管理器只能显示已下载字节数、看不到文件总大小（如 iOS 的 "140.5 MB/?"）；
            // 只有上游长度未知时才退回 chunked。
            val useChunked = contentLength == null
            output.writeBytes(
                buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $contentType\r\n")
                    if (useChunked) append("Transfer-Encoding: chunked\r\n")
                    else append("Content-Length: $contentLength\r\n")
                    if (plan.advertisesByteRanges) append("Accept-Ranges: bytes\r\n")
                    attachmentName?.let { append("Content-Disposition: attachment; filename=\"$it\"\r\n") }
                    append("Connection: close\r\n\r\n")
                }
            )
            writeBody(output, body, chunked = useChunked)
        }
        is MediaProxyPlan.RangeNotSatisfiable -> {
            val responseBody =
                """{"ok":false,"error":"请求的媒体范围超出文件长度"}"""
                    .toByteArray(StandardCharsets.UTF_8)
            output.writeBytes(
                rangeNotSatisfiableHeaders(plan.contentRange, responseBody.size)
            )
            output.write(responseBody)
            output.flush()
            body.close()
        }
        is MediaProxyPlan.UpstreamFailure -> Unit
    }
}

private fun writeBody(output: DataOutputStream, input: InputStream, chunked: Boolean) {
    input.use { stream ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (chunked) output.writeBytes("%X\r\n".format(count))
            output.write(buffer, 0, count)
            if (chunked) output.writeBytes("\r\n")
        }
    }
    if (chunked) output.writeBytes("0\r\n\r\n")
    output.flush()
}
