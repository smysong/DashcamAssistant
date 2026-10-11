package com.dashcam.assistant

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

internal class ThumbnailDiskCache(
    private val directory: File,
    private val maxBytes: Long = MediaCachePolicy.THUMBNAIL_CACHE_MAX_BYTES,
    private val minimumFreeBytes: Long = MediaCachePolicy.MIN_FREE_BYTES_FOR_CACHING
) {
    val temporaryDirectory = File(directory, "source")

    init {
        directory.mkdirs()
        directory.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        temporaryDirectory.mkdirs()
        temporaryDirectory.listFiles()?.forEach { it.delete() }
        val recoveredBytes = trim(keep = null)
        if (recoveredBytes > 0L) {
            AppLog.i("缩略图缓存", "清理超出上限的旧缓存：${recoveredBytes / 1024} KiB")
        }
    }

    @Synchronized
    fun get(key: String): File? {
        val file = cacheFile(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    @Synchronized
    fun put(key: String, write: (OutputStream) -> Unit): File? {
        if (maxBytes <= 0 || directory.usableSpace < minimumFreeBytes) return null
        directory.mkdirs()
        val destination = cacheFile(key)
        val temporary = File(directory, "${destination.name}.${Thread.currentThread().id}.tmp")
        try {
            FileOutputStream(temporary).use(write)
            if (!temporary.isFile || temporary.length() == 0L || temporary.length() > maxBytes) {
                return null
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("无法替换旧的缩略图缓存")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("无法写入缩略图缓存文件")
            }
            destination.setLastModified(System.currentTimeMillis())
            trim(destination)
            return destination.takeIf { it.exists() }
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun trim(keep: File?): Long {
        val files = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }
            ?: return 0L
        var total = files.sumOf { it.length() }
        val initialTotal = total
        for (file in files) {
            if (total <= maxBytes) break
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
        if (total > maxBytes && keep?.exists() == true) {
            val size = keep.length()
            if (keep.delete()) total -= size
        }
        return (initialTotal - total).coerceAtLeast(0L)
    }

    private fun cacheFile(key: String): File = File(directory, "${key.sha256()}.jpg")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal class LimitedOutputStream(
    output: OutputStream,
    private val maxBytes: Long
) : java.io.FilterOutputStream(output) {
    private var writtenBytes = 0L

    override fun write(value: Int) {
        ensureCapacity(1)
        out.write(value)
        writtenBytes++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        out.write(buffer, offset, length)
        writtenBytes += length
    }

    private fun ensureCapacity(nextBytes: Int) {
        if (nextBytes < 0 || writtenBytes + nextBytes > maxBytes) {
            throw IOException("图片源文件超过缩略图读取上限 ${maxBytes / (1024 * 1024)} MB")
        }
    }
}
