package com.dashcam.assistant.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpBackendIntegrationTest {
    @Test
    fun enumeratesKnownDirectoryAndStreamsMediaFromDvr() = runBlocking {
        val media = ByteArray(192 * 1024) { (it % 239).toByte() }
        FakeDvr { path ->
            when (path) {
                "/SD0/NOR/M_video/" -> Response(
                    200,
                    "text/html; charset=utf-8",
                    """<html><a href="NOR_20260921_041624A.MP4">clip</a></html>""".toByteArray()
                )
                "/SD0/NOR/M_video/NOR_20260921_041624A.MP4" ->
                    Response(200, "application/octet-stream", media)
                else -> Response(404, "text/plain", byteArrayOf())
            }
        }.use { dvr ->
            val backend = HttpBackend("127.0.0.1", dvr.port)

            val page = backend.listMediaPaged("video", 0, 10)
            val output = java.io.ByteArrayOutputStream()
            val progress = mutableListOf<Long>()
            val copied = backend.copyFileTo(page.items.single().path, output) { bytes, total ->
                assertEquals(media.size.toLong(), total)
                progress += bytes
            }

            assertEquals(1, page.total)
            assertEquals("NOR_20260921_041624A.MP4", page.items.single().name)
            assertEquals(media.size.toLong(), copied)
            assertArrayEquals(media, output.toByteArray())
            assertEquals(media.size.toLong(), progress.last())
        }
    }

    @Test
    fun liveUrlUsesObservedRtspEndpointWithoutInventingCredentialsOrPath() {
        assertEquals("rtsp://192.168.1.20/live", HttpBackend("192.168.1.20").liveUrl())
        assertEquals("", HttpBackend("").liveUrl())
    }

    @Test
    fun reportsDirectoryServerFailureInsteadOfTreatingItAsAnEmptyLibrary() {
        FakeDvr { Response(503, "text/plain", "unavailable".toByteArray()) }.use { dvr ->
            val backend = HttpBackend("127.0.0.1", dvr.port)

            val error = assertThrows(java.io.IOException::class.java) {
                runBlocking { backend.listMedia("video") }
            }

            assertTrue(error.message?.contains("HTTP 503") == true)
        }
    }

    @Test
    fun cancellationDisconnectsAnActiveMediaTransfer() = runBlocking {
        val firstChunkSent = CountDownLatch(1)
        val media = ByteArray(512 * 1024)
        FakeDvr {
            Response(200, "video/mp4", media, 200, firstChunkSent)
        }.use { dvr ->
            val output = java.io.ByteArrayOutputStream()
            val backend = HttpBackend("127.0.0.1", dvr.port)
            val transfer = launch { backend.copyFileTo("/recording.mp4", output) }
            assertTrue(withContext(Dispatchers.IO) { firstChunkSent.await(2, TimeUnit.SECONDS) })

            val startedAt = System.nanoTime()
            transfer.cancelAndJoin()

            assertTrue("network read should be interrupted promptly", System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(3))
            assertTrue(output.size() < media.size)
        }
    }

    @Test
    fun cancellationDisconnectsAnActiveDirectoryRequest() = runBlocking {
        val firstChunkSent = CountDownLatch(1)
        val body = ByteArray(512 * 1024).also {
            "<html>".toByteArray().copyInto(it)
        }
        FakeDvr {
            Response(200, "text/html", body, chunkDelayMillis = 500, firstChunkSent = firstChunkSent)
        }.use { dvr ->
            val backend = HttpBackend("127.0.0.1", dvr.port)
            val listing = launch { backend.listMedia("video") }
            assertTrue(withContext(Dispatchers.IO) { firstChunkSent.await(2, TimeUnit.SECONDS) })

            val startedAt = System.nanoTime()
            listing.cancelAndJoin()

            assertTrue("directory read should be interrupted promptly", System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(3))
            assertTrue(listing.isCancelled)
        }
    }

    private data class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val chunkDelayMillis: Long = 0,
        val firstChunkSent: CountDownLatch? = null
    )

    private class FakeDvr(private val respond: (String) -> Response) : Closeable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        private val acceptThread = Thread {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    Thread { handle(socket) }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                    if (!server.isClosed) throw IllegalStateException("Fake DVR accept failed")
                }
            }
        }.apply { isDaemon = true; start() }

        private fun handle(socket: Socket) {
            try {
                socket.use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                    val response = respond(path)
                    val reason = if (response.status == 200) "OK" else "Service Unavailable"
                    val output = DataOutputStream(client.getOutputStream())
                    output.writeBytes(
                        "HTTP/1.1 ${response.status} $reason\r\n" +
                            "Content-Type: ${response.contentType}\r\n" +
                            "Content-Length: ${response.body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    )
                    var offset = 0
                    while (offset < response.body.size) {
                        val count = minOf(16 * 1024, response.body.size - offset)
                        output.write(response.body, offset, count)
                        output.flush()
                        offset += count
                        if (offset == count) response.firstChunkSent?.countDown()
                        if (response.chunkDelayMillis > 0) Thread.sleep(response.chunkDelayMillis)
                    }
                }
            } catch (_: java.io.IOException) {
                // A client disconnect is expected in cancellation tests.
            }
        }

        override fun close() {
            server.close()
            acceptThread.join(1000)
        }
    }
}
