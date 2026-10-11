package com.dashcam.assistant.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MediaTransferTest {
    @Test
    fun copiesMediaInBoundedChunksAndReportsFinalByteCount() = runBlocking {
        val source = ByteArray(180_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()

        val copied = copyMediaStream(
            ByteArrayInputStream(source),
            output,
            source.size.toLong()
        ) { count, _ -> progress += count }

        assertEquals(source.size.toLong(), copied)
        assertArrayEquals(source, output.toByteArray())
        assertEquals(source.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (previous, current) -> current > previous })
    }

    @Test
    fun acceptsOnlySyntacticallyAndSemanticallyValidByteContentRanges() {
        assertTrue(isValidContentRange("bytes 0-9/10"))
        assertTrue(isValidContentRange("bytes 0-9/10", 10))
        assertFalse(isValidContentRange("bytes 0-9/10", 9))
        assertTrue(isValidContentRange("bytes 10-19/*"))
        assertFalse(isValidContentRange(null))
        assertFalse(isValidContentRange("items 0-9/10"))
        assertFalse(isValidContentRange("bytes 10-9/20"))
        assertFalse(isValidContentRange("bytes 0-10/10"))
        assertEquals("bytes */10", validUnsatisfiedContentRange("bytes */10"))
        assertEquals(null, validUnsatisfiedContentRange("bytes 10-11/10"))
    }

    @Test
    fun proxyPreservesOnlyVerifiedRangeSemantics() {
        assertEquals(
            MediaProxyPlan.Partial("bytes 0-9/10"),
            mediaProxyPlan(206, "bytes 0-9/10", "bytes", 10)
        )
        assertEquals(
            MediaProxyPlan.UpstreamFailure(206),
            mediaProxyPlan(206, "bytes 0-10/10", "bytes", 11)
        )
        assertEquals(
            MediaProxyPlan.Full(false),
            mediaProxyPlan(200, null, null)
        )
        assertEquals(
            MediaProxyPlan.Full(true),
            mediaProxyPlan(200, null, "bytes")
        )
        assertEquals(
            MediaProxyPlan.RangeNotSatisfiable("bytes */10"),
            mediaProxyPlan(416, "bytes */10", "bytes")
        )
        assertEquals(
            MediaProxyPlan.RangeNotSatisfiable(null),
            mediaProxyPlan(416, "bytes 0-10/10", "bytes")
        )
        assertEquals(
            MediaProxyPlan.UpstreamFailure(503),
            mediaProxyPlan(503, null, null)
        )
        val rangeFailureHeaders = rangeNotSatisfiableHeaders("bytes */10", 24)
        assertTrue(rangeFailureHeaders.startsWith("HTTP/1.1 416 Range Not Satisfiable\r\n"))
        assertTrue(rangeFailureHeaders.contains("Content-Range: bytes */10\r\n"))
        assertTrue(rangeFailureHeaders.contains("Content-Length: 24\r\n"))
        assertFalse(rangeNotSatisfiableHeaders("malformed", 24).contains("Content-Range:"))
    }

    @Test
    fun cancellationStopsTheNextMediaRead() = runBlocking {
        val output = ByteArrayOutputStream()
        val job = launch {
            copyMediaStream(ByteArrayInputStream(ByteArray(200_000)), output, null) { copied, _ ->
                if (copied >= 64 * 1024) {
                    currentCoroutineContext().cancel()
                }
            }
        }

        job.join()

        assertTrue(job.isCancelled)
        assertEquals(64 * 1024, output.size())
    }
}
