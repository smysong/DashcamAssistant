package com.dashcam.assistant.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.test.InstrumentationTestCase
import com.dashcam.assistant.HttpBackend
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class MobileThumbnailServiceTest : InstrumentationTestCase() {
    fun testPhotoThumbnailIsDecodedScaledAndCachedOnDisk() {
        val context: Context = instrumentation.targetContext
        val photo = createPhotoSample()
        PhotoHttpFixture(photo).use { fixture ->
            val path = "/SD0/PHO/M_photo/test-${System.nanoTime()}.jpg"
            val service = MobileThumbnailService(context) {
                HttpBackend("127.0.0.1", fixture.port)
            }

            val first = service.get(path)
            val thumbnailBytes = first.readBytes()
            assertTrue(thumbnailBytes.size > 4)
            assertEquals(0xff.toByte(), thumbnailBytes[0])
            assertEquals(0xd8.toByte(), thumbnailBytes[1])
            val decoded = BitmapFactory.decodeFile(first.absolutePath)
                ?: error("Generated JPEG thumbnail was not decodable")
            try {
                assertTrue(decoded.width <= 360)
                assertTrue(decoded.height <= 360)
                val second = service.get(path)
                assertEquals(first.canonicalPath, second.canonicalPath)
                assertEquals("cache hit should not download the photo again", 1, fixture.requestCount)
            } finally {
                decoded.recycle()
            }
        }
    }

    private fun createPhotoSample(): ByteArray {
        val bitmap = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).drawColor(Color.rgb(20, 130, 210))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class PhotoHttpFixture(private val photo: ByteArray) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        @Volatile var requestCount = 0
            private set
        private val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                while (!reader.readLine().isNullOrEmpty()) Unit
                requestCount++
                socket.getOutputStream().use { output ->
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${photo.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.ISO_8859_1)
                    )
                    output.write(photo)
                    output.flush()
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }
}
