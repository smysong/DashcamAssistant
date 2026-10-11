package com.dashcam.assistant

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

internal object MediaCachePolicy {
    const val PLAYBACK_CACHE_MAX_BYTES = 512L * 1024 * 1024
    const val MIN_FREE_BYTES_FOR_CACHING = 32L * 1024 * 1024
    const val THUMBNAIL_CACHE_MAX_BYTES = 256L * 1024 * 1024
    const val MAX_THUMBNAIL_SOURCE_BYTES = 24L * 1024 * 1024
    // 视频缩略图取首帧时，经 Range 请求最多拉取的字节数：足以覆盖绝大多数 MP4 的首个关键帧，
    // 又避免 MediaMetadataRetriever 对远程直链从头拉整段（DVR 是嵌入式小服务器，整段拉取既慢又压并发）。
    const val MAX_THUMBNAIL_FETCH_BYTES = 8L * 1024 * 1024
    const val MAX_FULL_PHOTO_SOURCE_BYTES = 64L * 1024 * 1024
    const val THUMBNAIL_MAX_DIMENSION = 360
    const val FULL_PHOTO_MAX_DIMENSION = 1600

    fun shouldUsePlaybackDiskCache(availableBytes: Long): Boolean =
        availableBytes >= MIN_FREE_BYTES_FOR_CACHING
}

internal data class PlaybackDataSource(
    val factory: DataSource.Factory,
    val warning: String? = null
)

internal object MediaPlaybackDataSource {
    private const val PLAYBACK_CACHE_DIR = "media-playback"

    @Volatile
    private var cache: SimpleCache? = null

    /** 清空回放磁盘缓存目录并释放 SimpleCache 实例（设置页「清除回放缓存」调用）。返回释放的字节数。 */
    fun clearCache(context: Context): Long {
        val dir = File(context.applicationContext.cacheDir, PLAYBACK_CACHE_DIR)
        cache?.let {
            try { it.release() } catch (_: Exception) { }
            cache = null
        }
        var freed = 0L
        if (dir.exists()) {
            freed = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            dir.deleteRecursively()
        }
        return freed
    }

    fun create(context: Context, onCacheWarning: (String) -> Unit = {}): PlaybackDataSource {
        val appContext = context.applicationContext
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        val upstreamFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val cacheDirectory = File(appContext.cacheDir, PLAYBACK_CACHE_DIR)
        cacheDirectory.mkdirs()
        val availableBytes = cacheDirectory.usableSpace
        if (!MediaCachePolicy.shouldUsePlaybackDiskCache(availableBytes)) {
            val warning = "可用空间不足，已停用回放磁盘缓存；本次仍以流式方式播放"
            AppLog.w("媒体缓存", "$warning（可用 ${availableBytes / (1024 * 1024)} MB）")
            onCacheWarning(warning)
            return PlaybackDataSource(upstreamFactory, warning)
        }

        return try {
            val diskCache = synchronized(this) {
                cache ?: SimpleCache(
                    cacheDirectory,
                    LeastRecentlyUsedCacheEvictor(MediaCachePolicy.PLAYBACK_CACHE_MAX_BYTES),
                    StandaloneDatabaseProvider(appContext)
                ).also {
                    cache = it
                    AppLog.i(
                        "媒体缓存",
                        "回放磁盘缓存已启用：${cacheDirectory.absolutePath}，上限 ${MediaCachePolicy.PLAYBACK_CACHE_MAX_BYTES / (1024 * 1024)} MB"
                    )
                }
            }
            val cachedFactory = CacheDataSource.Factory()
                .setCache(diskCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setEventListener(object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) = Unit

                    override fun onCacheIgnored(reason: Int) {
                        val warning = "磁盘缓存不可用，已改为直接流式读取（原因码 $reason）"
                        AppLog.w("媒体缓存", warning)
                        onCacheWarning(warning)
                    }
                })
            PlaybackDataSource(cachedFactory)
        } catch (e: Exception) {
            val warning = "无法初始化回放磁盘缓存，已改为直接流式播放：${e.message ?: "未知错误"}"
            AppLog.w("媒体缓存", warning)
            onCacheWarning(warning)
            PlaybackDataSource(upstreamFactory, warning)
        }
    }
}
