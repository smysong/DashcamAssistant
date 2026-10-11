package com.dashcam.assistant.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketException
import java.io.DataOutputStream

class MediaProxyResponseTest {
    @Test
    fun recognizesDownstreamDisconnectErrorsButNotUpstreamFailures() {
        assertTrue(isDownstreamClientDisconnect(SocketException("Broken pipe")))
        assertTrue(isDownstreamClientDisconnect(IOException("Connection reset by peer")))
        assertFalse(isDownstreamClientDisconnect(IOException("DVR returned HTTP 500")))
    }

    @Test
    fun fullResponseUsesChunkedBodyAndDoesNotInventRangeSupport() {
        val raw = writeResponse(
            plan = MediaProxyPlan.Full(advertisesByteRanges = false),
            contentLength = null,
            body = "video".toByteArray()
        )
        val response = raw.toString(Charsets.ISO_8859_1)

        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("Transfer-Encoding: chunked\r\n"))
        assertFalse(response.contains("Accept-Ranges:"))
        assertTrue(response.endsWith("\r\n\r\n5\r\nvideo\r\n0\r\n\r\n"))
    }

    @Test
    fun partialResponsePreservesRangeHeadersAndRawBody() {
        val raw = writeResponse(
            plan = MediaProxyPlan.Partial("bytes 4-7/8"),
            contentLength = 4,
            body = byteArrayOf(1, 2, 3, 4),
            attachmentName = "clip.mp4"
        )
        val header = raw.toString(Charsets.ISO_8859_1).substringBefore("\r\n\r\n")

        assertTrue(header.startsWith("HTTP/1.1 206 Partial Content\r\n"))
        assertTrue(header.contains("Content-Range: bytes 4-7/8\r\n"))
        assertTrue(header.contains("Content-Length: 4\r\n"))
        assertTrue(header.contains("Accept-Ranges: bytes\r\n"))
        assertTrue(header.contains("Content-Disposition: attachment; filename=\"clip.mp4\"\r\n"))
        assertFalse(header.contains("Transfer-Encoding: chunked"))
        assertTrue(raw.takeLast(4).toByteArray().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun unsatisfiedRangeReturns416AndValidLengthMetadata() {
        val raw = writeResponse(
            plan = MediaProxyPlan.RangeNotSatisfiable("bytes */12"),
            contentLength = null,
            body = byteArrayOf()
        )
        val response = raw.toString(Charsets.UTF_8)

        assertTrue(response.startsWith("HTTP/1.1 416 Range Not Satisfiable\r\n"))
        assertTrue(response.contains("Content-Range: bytes */12\r\n"))
        assertTrue(response.contains("请求的媒体范围超出文件长度"))
    }

    @Test
    fun malformedPartialPlanDoesNotEmitSuccessResponse() {
        val raw = writeResponse(
            plan = MediaProxyPlan.UpstreamFailure(206),
            contentLength = 10,
            body = byteArrayOf(1)
        )

        assertTrue(raw.isEmpty())
    }

    private fun writeResponse(
        plan: MediaProxyPlan,
        contentLength: Long?,
        body: ByteArray,
        attachmentName: String? = null
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeMediaProxyResponse(
            DataOutputStream(output),
            plan,
            "video/mp4",
            contentLength,
            attachmentName,
            ByteArrayInputStream(body)
        )
        return output.toByteArray()
    }
}
