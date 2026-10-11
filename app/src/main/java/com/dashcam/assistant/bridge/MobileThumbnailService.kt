package com.dashcam.assistant.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.dashcam.assistant.LimitedOutputStream
import com.dashcam.assistant.MediaCachePolicy
import com.dashcam.assistant.ThumbnailDiskCache
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class MobileThumbnailService(
    context: Context,
    private val backendProvider: () -> DvrBackend?
) {
    private val cache = ThumbnailDiskCache(File(context.cacheDir, "media-thumbnails"))
    private val maxDimension = MediaCachePolicy.THUMBNAIL_MAX_DIMENSION

    fun get(path: String): File {
        val mime = guessMediaContentType(path, null)
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
            throw IOException("不支持此媒体类型的缩略图")
        }
        val key = "mobile:$path"
        cache.get(key)?.let { return it }
        val bitmap = if (mime.startsWith("image/")) {
            createImageThumbnail(path)
        } else {
            createVideoThumbnail(path)
        }
        try {
            return cache.put(key) { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                    throw IOException("缩略图 JPEG 编码失败")
                }
            } ?: throw IOException("缩略图缓存空间不足或写入失败")
        } finally {
            bitmap.recycle()
        }
    }

    private fun createImageThumbnail(path: String): Bitmap {
        val cacheDirectory = cache.temporaryDirectory
        if (cacheDirectory.usableSpace < MediaCachePolicy.MIN_FREE_BYTES_FOR_CACHING +
            MediaCachePolicy.MAX_THUMBNAIL_SOURCE_BYTES
        ) {
            throw IOException("可用空间不足，无法生成图片缩略图")
        }
        val source = File.createTempFile("mobile-thumb-", ".tmp", cacheDirectory)
        try {
            URL(backendUrl(path)).openConnection().asHttpConnection().useConnection { connection ->
                val length = connection.contentLengthLong
                if (length > MediaCachePolicy.MAX_THUMBNAIL_SOURCE_BYTES) {
                    throw IOException("图片源文件超过缩略图读取上限")
                }
                source.outputStream().use { output ->
                    connection.inputStream.use { input ->
                        LimitedOutputStream(output, MediaCachePolicy.MAX_THUMBNAIL_SOURCE_BYTES).use { limited ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                limited.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            return decodeSampledImage(source)
        } finally {
            source.delete()
        }
    }

    private fun createVideoThumbnail(path: String): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw IOException("视频缩略图需要 Android 8.0 或更新版本")
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(backendUrl(path), emptyMap())
            retriever.getScaledFrameAtTime(
                0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                maxDimension,
                maxDimension
            ) ?: throw IOException("无法从视频提取首帧")
        } finally {
            retriever.release()
        }
    }

    private fun decodeSampledImage(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法解码图片尺寸")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return FileInputStream(file).use { input ->
            BitmapFactory.decodeStream(input, null, options) ?: throw IOException("图片缩略图解码失败")
        }
    }

    private fun backendUrl(path: String): String {
        if (path.isBlank() || !path.startsWith("/")) throw IOException("无效的媒体路径")
        val backend = backendProvider()
        return backend?.directUrl(path) ?: throw IOException("DVR 未连接")
    }

    private fun java.net.URLConnection.asHttpConnection(): HttpURLConnection {
        val connection = this as? HttpURLConnection ?: throw IOException("DVR 地址不是 HTTP")
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            throw IOException("DVR 返回 HTTP $status")
        }
        return connection
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }
}

internal fun mobileThumbnailUrl(path: String): String =
    "/api/thumb?path=" + java.net.URLEncoder.encode(path, Charsets.UTF_8.name())
