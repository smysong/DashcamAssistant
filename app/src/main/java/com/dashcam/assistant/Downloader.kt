package com.dashcam.assistant

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.mutableStateMapOf
import com.dashcam.assistant.bridge.guessMediaContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载辅助：优先交给系统默认下载器（DownloadManager）下载到公共 Download 目录，
 * 由系统负责通知/进度/断点续传；系统下载器不可用时由调用方回退到应用内置下载
 * （saveMediaToLibrary，保存到系统媒体库 Movies|Pictures/DashcamAssistant）。
 */

/** 系统下载器是否可用：DownloadManager 存在即可（下载到公共 Download 目录无需额外权限）。 */
fun systemDownloadAvailable(context: Context): Boolean {
    return context.getSystemService(Context.DOWNLOAD_SERVICE) is DownloadManager
}

/**
 * 交给系统 DownloadManager 下载到公共 Download 目录（DownloadManager 自动处理重名与完成通知）。
 * 返回下载任务 id；失败返回 -1。进度通过 [DownloadTracker.trackSystemDownload] 轮询展示。
 */
fun enqueueSystemDownload(context: Context, url: String, title: String, mimeType: String?): Long {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return -1L
    if (url.isBlank()) return -1L
    val request = DownloadManager.Request(Uri.parse(url)).apply {
        setTitle(title)
        setDescription("行车记录仪助手下载")
        if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, title)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setAllowedOverMetered(true)
        setAllowedOverRoaming(true)
    }
    return try {
        dm.enqueue(request)
    } catch (e: Exception) {
        AppLog.e("下载", "系统下载器入队失败：${e.message}")
        -1L
    }
}

/** 根据媒体名推断 MIME（下载器需要正确的 MIME 才能正确落盘）。 */
fun mediaMimeType(name: String): String = guessMediaContentType(name, null)

/** 单个下载任务的进度快照（key 一般为媒体 path，天然去重）。 */
data class DownloadProgress(
    val key: String,
    val title: String,
    val bytes: Long,
    val total: Long,
    val done: Boolean,
    val failed: Boolean
) {
    /** total 未知（<=0）时返回 null，UI 显示不确定进度。 */
    val fraction: Float?
        get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * 下载进度登记表：系统下载器任务由后台协程轮询 DownloadManager 更新；
 * 应用内置下载由 [startInternal] 托管协程更新。UI 读取 [progress]（Compose State）自动重组。
 * 任务结束后条目保留约 4 秒（让用户看到完成/失败状态）再自动移除，复位为可再次下载。
 * 所有任务均可通过 [cancel] 取消（系统任务 DownloadManager.remove，内置任务 Job.cancel）。
 */
object DownloadTracker {
    val progress = mutableStateMapOf<String, DownloadProgress>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val POLL_INTERVAL_MS = 400L
    private const val FINAL_KEEP_MS = 4_000L

    /** key -> 系统下载器轮询协程 */
    private val pollJobs = ConcurrentHashMap<String, Job>()
    /** key -> 系统下载器任务 id（用于取消时 DownloadManager.remove） */
    private val systemIds = ConcurrentHashMap<String, Long>()
    /** key -> 应用内置下载协程 */
    private val internalJobs = ConcurrentHashMap<String, Job>()
    @Volatile
    private var appContext: Context? = null

    /** 跟踪一个系统下载器任务，直到完成/失败（完成后延迟清理条目）。 */
    fun trackSystemDownload(context: Context, downloadId: Long, key: String, title: String) {
        if (downloadId <= 0L) return
        val appContext = context.applicationContext
        this.appContext = appContext
        systemIds[key] = downloadId
        progress[key] = DownloadProgress(key, title, 0L, -1L, done = false, failed = false)
        pollJobs[key]?.cancel()
        pollJobs[key] = scope.launch {
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) {
                progress[key] = DownloadProgress(key, title, 0L, -1L, done = false, failed = true)
                delay(FINAL_KEEP_MS)
                progress.remove(key)
                pollJobs.remove(key)
                systemIds.remove(key)
                return@launch
            }
            var misses = 0
            while (isActive) {
                val snapshot = queryDownload(dm, downloadId)?.let { (status, soFar, total) ->
                    DownloadProgress(
                        key = key,
                        title = title,
                        bytes = soFar,
                        total = total,
                        done = status == DownloadManager.STATUS_SUCCESSFUL,
                        failed = status == DownloadManager.STATUS_FAILED
                    )
                }
                if (snapshot == null) {
                    // 任务刚入队时可能查询不到，连续多次查不到（约 4 秒）按失败收尾
                    misses++
                    if (misses >= 10) {
                        progress[key] = DownloadProgress(key, title, 0L, -1L, done = false, failed = true)
                        delay(FINAL_KEEP_MS)
                        progress.remove(key)
                        pollJobs.remove(key)
                        systemIds.remove(key)
                        break
                    }
                } else {
                    misses = 0
                    if (snapshot.done) {
                        // 下载完成后把文件修改时间校正为完成时刻（尽力而为，见 fixDownloadFileTime）
                        fixDownloadFileTime(appContext, downloadId, title)
                    }
                    progress[key] = snapshot
                    if (snapshot.done || snapshot.failed) {
                        delay(FINAL_KEEP_MS)
                        progress.remove(key)
                        pollJobs.remove(key)
                        systemIds.remove(key)
                        break
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 托管一个应用内置下载协程：统一登记进度初值与 Job（供 [cancel] 取消）。
     * block 内部自行负责 done/failed 状态写入；用户取消时条目由 [cancel] 清理。
     */
    fun startInternal(key: String, title: String, block: suspend () -> Unit) {
        internalJobs.remove(key)?.cancel()
        progress[key] = DownloadProgress(key, title, 0L, -1L, done = false, failed = false)
        internalJobs[key] = scope.launch {
            try {
                block()
            } catch (_: CancellationException) {
                // 用户取消：cancel() 已移除条目，这里不再写状态
            } finally {
                internalJobs.remove(key)
            }
        }
    }

    /** 应用内置下载（回退）由保存协程直接更新进度；结束时置 done/failed 并延迟清理。 */
    fun update(key: String, title: String, bytes: Long, total: Long, done: Boolean = false, failed: Boolean = false) {
        progress[key] = DownloadProgress(key, title, bytes, total, done, failed)
        if (done || failed) {
            scope.launch {
                delay(FINAL_KEEP_MS)
                progress.remove(key)
            }
        }
    }

    /**
     * 取消指定下载：系统任务走 DownloadManager.remove（通知栏一并消失），
     * 内置任务走 Job.cancel（saveMediaToLibrary 会自动清理未完成的媒体库条目）。
     */
    fun cancel(key: String) {
        pollJobs.remove(key)?.cancel()
        internalJobs.remove(key)?.cancel()
        val id = systemIds.remove(key)
        if (id != null && id > 0L) {
            val ctx = appContext
            val dm = ctx?.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            try {
                dm?.remove(id)
                AppLog.i("下载", "已取消系统下载任务：$key")
            } catch (e: Exception) {
                AppLog.w("下载", "取消系统下载任务失败：${e.message}")
            }
        }
        progress.remove(key)
    }

    private fun queryDownload(dm: DownloadManager, id: Long): Triple<Int, Long, Long>? {
        var cursor: Cursor? = null
        return try {
            cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor == null || !cursor.moveToFirst()) {
                null
            } else {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val soFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                Triple(status, soFar, total)
            }
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * 下载完成后把文件修改时间校正为完成时刻（用户要求：文件时间=下载完成时间）。
     * 本地 Uri 拿不到（Q+ 可能返回 content://）时按「公共 Download 目录 + 标题」兜底定位。
     * setLastModified 在受限存储上可能失败——尽力而为，失败不影响下载结果。
     */
    private fun fixDownloadFileTime(ctx: Context, id: Long, title: String) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            var cursor: Cursor? = null
            val localUri = try {
                cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (cursor != null && cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                } else null
            } catch (_: Exception) {
                null
            } finally {
                cursor?.close()
            }
            val target = when {
                localUri != null && localUri.startsWith("file://") -> File(Uri.parse(localUri).path ?: "")
                else -> File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    title
                )
            }
            if (target.exists() && target.setLastModified(System.currentTimeMillis())) {
                AppLog.i("下载", "已把文件时间校正为下载完成时间：${target.name}")
            }
        } catch (_: Throwable) {
            // 尽力而为
        }
    }
}
