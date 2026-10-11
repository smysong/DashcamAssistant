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
    const val PLAYBACK_CACHE_MAX_BYTES = 256L * 1024 * 1024
    const val MIN_FREE_BYTES_FOR_CACHING = 32L * 1024 * 1024
    const val THUMBNAIL_CACHE_MAX_BYTES = 64L * 1024 * 1024
    const val MAX_THUMBNAIL_SOURCE_BYTES = 24L * 1024 * 1024
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

    fun create(context: Context, onCacheWarning: (String) -> Unit = {}): PlaybackDataSource {
        val appContext = context.applicationContext
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(30_000)
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
