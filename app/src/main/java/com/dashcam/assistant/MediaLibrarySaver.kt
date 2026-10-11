package com.dashcam.assistant

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dashcam.assistant.bridge.DvrBackend
import com.dashcam.assistant.bridge.MediaItem
import com.dashcam.assistant.bridge.guessMediaContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal suspend fun saveMediaToLibrary(
    context: Context,
    backend: DvrBackend,
    item: MediaItem,
    onProgress: suspend (copiedBytes: Long, totalBytes: Long?) -> Unit
): Uri = withContext(Dispatchers.IO) {
    val photo = item.type == "photo"
    val collection = if (photo) {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val name = safeMediaDisplayName(item.name)
    val mimeType = guessMediaContentType(name, null)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val directory = if (photo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/DashcamAssistant")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(
                    if (photo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
                ),
                "DashcamAssistant"
            )
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("无法创建媒体保存目录")
            }
            put(MediaStore.MediaColumns.DATA, File(directory, name).absolutePath)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values) ?: throw IOException("无法在媒体库创建文件")
    try {
        val output = resolver.openOutputStream(uri, "w") ?: throw IOException("无法打开媒体库文件")
        output.use { backend.copyFileTo(item.path, it, onProgress) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) {
                throw IOException("媒体文件已保存，但无法发布到媒体库")
            }
        }
        uri
    } catch (e: Exception) {
        try {
            if (resolver.delete(uri, null, null) == 0) {
                AppLog.w("媒体保存", "未能清理未完成的媒体库文件：$uri")
            }
        } catch (cleanupError: Exception) {
            e.addSuppressed(cleanupError)
            AppLog.e("媒体保存", "清理未完成的媒体库文件失败", cleanupError)
        }
        throw e
    }
}

private fun safeMediaDisplayName(name: String): String {
    val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    return safe.ifEmpty { "dashcam_${System.currentTimeMillis()}" }
}
