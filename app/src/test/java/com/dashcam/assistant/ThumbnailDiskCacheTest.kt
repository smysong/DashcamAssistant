package com.dashcam.assistant

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ThumbnailDiskCacheTest {
    @Test
    fun cacheEvictsOldEntriesToStayWithinItsByteLimit() {
        val directory = Files.createTempDirectory("thumbnail-cache").toFile()
        try {
            val cache = ThumbnailDiskCache(directory, maxBytes = 10, minimumFreeBytes = 0)
            val first = cache.put("first") { it.write(ByteArray(7) { 1 }) }
            assertNotNull(first)
            first!!.setLastModified(1)

            val second = cache.put("second") { it.write(ByteArray(7) { 2 }) }

            assertNotNull(second)
            assertNull(cache.get("first"))
            assertArrayEquals(ByteArray(7) { 2 }, second!!.readBytes())
            assertEquals(7, directory.listFiles()!!.filter { it.isFile }.sumOf { it.length() }.toInt())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesCacheWritesWhenFreeSpaceReserveIsNotMet() {
        val directory = Files.createTempDirectory("thumbnail-cache-low-space").toFile()
        try {
            val cache = ThumbnailDiskCache(directory, maxBytes = 100, minimumFreeBytes = Long.MAX_VALUE)

            assertNull(cache.put("key") { it.write(byteArrayOf(1)) })
            assertFalse(directory.listFiles()!!.any { it.isFile })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedEntriesAreDiscardedAndTemporaryFilesAreCleaned() {
        val directory = Files.createTempDirectory("thumbnail-cache-oversized").toFile()
        try {
            val cache = ThumbnailDiskCache(directory, maxBytes = 4, minimumFreeBytes = 0)

            assertNull(cache.put("key") { it.write(ByteArray(5)) })
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun limitedOutputStreamRejectsImagesPastTheConfiguredLimit() {
        val output = ByteArrayOutputStream()
        val limited = LimitedOutputStream(output, maxBytes = 3)
        limited.write(byteArrayOf(1, 2, 3))

        assertThrows(IOException::class.java) { limited.write(4) }
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }
}
