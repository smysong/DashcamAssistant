package com.dashcam.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dashcam.assistant.AppLog
import com.dashcam.assistant.MediaNameDisplayMode
import com.dashcam.assistant.MediaNameDisplayPreference
import com.dashcam.assistant.formatMediaName
import com.dashcam.assistant.mediaTimestamp
import com.dashcam.assistant.bridge.DvrBackend
import com.dashcam.assistant.bridge.MediaItem
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 车机端原生媒体管理界面（对应手机扫码后的管理体验，但无需手机）。
 * 三个媒体分页与独立车机 RTSP 实时预览入口。
 *
 * 媒体资源策略：
 * - 视频首帧由 MediaMetadataRetriever 生成；照片按大小上限流式写临时文件后采样解码；缩略图以有界磁盘 LRU 缓存。
 * - 点播使用 Media3 Progressive 播放和有界磁盘缓存；车机直播由 Media3 RTSP/TCP 直接播放。
 * - Range、设备编码和实际解码兼容性仍需目标设备验证。
 * - HTTP 模式无删除端点；保存使用 MediaStore 流式写入，提供进度、取消和错误提示。
 */
enum class MediaTab(val type: String, val label: String) {
    VIDEO("video", "普通录像"),
    EVENT("event", "紧急录像"),
    PHOTO("photo", "照片"),
    LIVE("live", "实时预览")
}

private val mediaPhotoTransferSemaphore = Semaphore(1)

internal object MediaThumbnailCache {
    @Volatile private var instance: ThumbnailDiskCache? = null

    fun get(context: android.content.Context): ThumbnailDiskCache = instance ?: synchronized(this) {
        instance ?: ThumbnailDiskCache(File(context.applicationContext.cacheDir, "media-thumbnails"))
            .also {
                instance = it
                AppLog.i(
                    "缩略图缓存",
                    "磁盘缓存目录 ${File(context.applicationContext.cacheDir, "media-thumbnails").absolutePath}，上限 ${MediaCachePolicy.THUMBNAIL_CACHE_MAX_BYTES / (1024 * 1024)} MB"
                )
            }
    }
}

@Composable
fun MediaBrowserScreen(onBack: () -> Unit, initialTab: MediaTab = MediaTab.VIDEO) {
    val backend by BridgeState.backendState.collectAsState()
    val status by BridgeState.status.collectAsState()
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var nameDisplayMode by remember { mutableStateOf(MediaNameDisplayPreference.load(context)) }
    var showSettings by remember { mutableStateOf(false) }
    // 下载策略：优先系统默认下载器（DownloadManager，公共 Download 目录）；
    // 系统下载器不可用时回退应用内置下载（保存到系统媒体库 Movies|Pictures/DashcamAssistant）。
    // 进度统一写入 DownloadTracker（Compose State），缩略图按钮与全屏保存面板据此显示进度。
    val onRequestDownload: (DvrBackend?, MediaItem) -> Unit = { b, item ->
        if (b?.isReady() != true) {
            Toast.makeText(context, "DVR 未连接，无法下载", Toast.LENGTH_SHORT).show()
        } else if (systemDownloadAvailable(context)) {
            val id = enqueueSystemDownload(context, b.directUrl(item.path).orEmpty(), item.name, mediaMimeType(item.name))
            if (id > 0) {
                DownloadTracker.trackSystemDownload(context, id, item.path, item.name)
                Toast.makeText(context, "已交给系统下载器：${item.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "下载启动失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "系统下载器不可用，已改用应用内置下载", Toast.LENGTH_SHORT).show()
            val appContext = context.applicationContext
            DownloadTracker.startInternal(item.path, item.name) {
                try {
                    saveMediaToLibrary(appContext, b, item) { copied, total ->
                        DownloadTracker.update(item.path, item.name, copied, total ?: -1L)
                    }
                    DownloadTracker.update(item.path, item.name, 0L, -1L, done = true)
                    AppLog.i("下载", "内置下载完成：${item.name}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DownloadTracker.update(item.path, item.name, 0L, -1L, failed = true)
                    AppLog.e("下载", "内置下载失败：${item.name}（${e.message}）")
                }
            }
        }
    }
    var tab by remember { mutableStateOf(initialTab) }
    var mediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var selectedVideo by remember { mutableStateOf<MediaItem?>(null) }
    var selectedPhoto by remember { mutableStateOf<MediaItem?>(null) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    // 分页状态：每页数量默认 10，可选 10/15/20
    var pageOffset by remember { mutableStateOf(0) }
    var pageSize by remember { mutableStateOf(PageSizePreference.load(context)) }
    var pageTotal by remember { mutableStateOf(0) }

    /** 加载指定页（默认不刷新缓存；refresh=true 时强制重新扫描 DVR）。
     *  两段式：先读磁盘缓存即时渲染（冷启动不转圈），再后台 scan 刷新并覆盖更新。 */
    fun loadPage(type: String, offset: Int, limit: Int, refresh: Boolean = false) {
        loadJob?.cancel()
        val b = backend
        val generation = ++loadGeneration
        if (b?.isReady() != true) {
            mediaItems = emptyList()
            pageTotal = 0
            loadError = ""
            loading = false
            loadJob = null
            return
        }
        loadJob = scope.launch {
            loadError = ""
            // 阶段一：磁盘缓存即时渲染（无网络）。refresh 时跳过，直接进入加载态重新扫描。
            if (!refresh) {
                val cached = withContext(Dispatchers.IO) { b.getCachedListPage(type, offset, limit) }
                if (generation == loadGeneration && tab.type == type && backend === b) {
                    if (cached != null) {
                        mediaItems = cached.items
                        pageTotal = cached.total
                        pageOffset = cached.offset
                        loading = false
                    } else {
                        loading = true
                    }
                }
            } else {
                loading = true
            }
            // 阶段二：后台权威扫描（写盘），完成后覆盖更新
            try {
                if (refresh) b.invalidateCache(type)
                val page = withContext(Dispatchers.IO) { b.listMediaPaged(type, offset, limit) }
                if (generation == loadGeneration && tab.type == type && backend === b) {
                    mediaItems = page.items
                    pageTotal = page.total
                    pageOffset = page.offset
                    loading = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration && tab.type == type && backend === b) {
                    // 仅当阶段一无任何缓存可显示时才报错
                    if (mediaItems.isEmpty()) {
                        mediaItems = emptyList()
                        pageTotal = 0
                        loadError = e.message ?: "读取 DVR 媒体失败"
                    }
                    loading = false
                }
            } finally {
                if (generation == loadGeneration) {
                    loadJob = null
                }
            }
        }
    }

    // 切换分页 / 后端就绪后加载第一页
    LaunchedEffect(tab, backend) {
        val b = backend
        if (tab == MediaTab.LIVE || b == null || !b.isReady()) {
            loadGeneration++
            loadJob?.cancel()
            loadJob = null
            loading = false
            loadError = ""
            mediaItems = emptyList(); pageTotal = 0
            return@LaunchedEffect
        }
        pageOffset = 0
        loadPage(tab.type, 0, pageSize)
    }

    // systemBarsPadding：Android 15+（targetSdk 35）会强制 edge-to-edge，
    // 不做避让时顶部“返回”按钮会被状态栏盖住；旧设备上 insets 为 0，视觉无变化。
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val metrics = dashcamLayoutMetrics(dashcamWindowClass())
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(
                    horizontal = metrics.browserHorizontalPadding,
                    vertical = metrics.browserVerticalPadding
                )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { AppLog.i("车机", "返回主页"); onBack() },
                    modifier = Modifier.heightIn(min = 52.dp)
                ) { Text("返回") }
                Text(
                    if (tab == MediaTab.LIVE) "实时预览" else "媒体库",
                    Modifier.weight(1f).padding(start = 16.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 设备地址可能较长：单行 + 省略号，避免挤压标题或换行破坏整行对齐
                Text(
                    status.dvrHost.ifEmpty { "未连接" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { showSettings = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 实时预览由首页独立入口进入（route="live"），媒体库内不再放入口按钮，
            // 预览页左上角的「返回」直接回主页；分类 Tab 仅在媒体库三个分类下显示。
            if (tab != MediaTab.LIVE) {
                MediaCategoryPills(current = tab, onSelect = { t ->
                    AppLog.i("车机", "切换分类：${t.label}")
                    tab = t
                    pageOffset = 0
                })
            }

            when (tab) {
                MediaTab.LIVE -> LiveTab(backend = backend, modifier = Modifier.weight(1f))
                else -> {
                    val ready = backend?.isReady() == true
                    when {
                        !ready -> CenteredText("DVR 未就绪，请先连接")
                        loading -> MediaGridLoading()
                        loadError.isNotEmpty() -> Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(44.dp))
                            Text("无法读取媒体", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                            Text(loadError, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            FilledTonalButton(onClick = { loadPage(tab.type, pageOffset, pageSize, refresh = true) }, modifier = Modifier.padding(top = 12.dp).heightIn(min = 52.dp)) {
                                Text("重试")
                            }
                        }
                        mediaItems.isEmpty() -> MediaEmptyState(
                            title = if (tab == MediaTab.PHOTO) "暂无照片" else "暂无录像",
                            subtitle = "可重新扫描设备目录，查看是否有新媒体",
                            onRefresh = { loadPage(tab.type, 0, pageSize, refresh = true) }
                        )
                        else -> Column(Modifier.fillMaxSize()) {
                            // 按日期分栏：同一天的录像/照片归到同一栏，栏头标注日期与该栏数量
                            val dateGroups = remember(mediaItems) { groupMediaByDate(mediaItems) }
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = metrics.gridMinCell),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            ) {
                                dateGroups.forEach { group ->
                                    item(key = "date-${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                                        MediaDateHeader(group)
                                    }
                                    items(group.items, key = { it.path }) { item ->
                                        MediaTile(
                                            backend = backend,
                                            item = item,
                                            isEvent = tab == MediaTab.EVENT,
                                            nameDisplayMode = nameDisplayMode,
                                            onOpen = {
                                                AppLog.i("车机", "打开${if (item.type == "photo") "照片" else "视频"}：${item.name}")
                                                if (item.type == "photo") selectedPhoto = item else selectedVideo = item
                                            },
                                            onRequestDownload = onRequestDownload
                                        )
                                    }
                                }
                            }
                            // 底部控制条单行化：翻页组在左，每页数量（下拉）与文件名显示（下拉）在右。
                            // 横向可滚动兜底：车机横屏一屏放下；窄屏（手机竖屏）内容超宽时左右滑动可用，
                            // 且内容不足一屏时仍由 SpaceBetween 左右分布，视觉与固定一行一致。
                            val totalPages = maxOf(1, (pageTotal + pageSize - 1) / pageSize)
                            val curPage = pageOffset / pageSize + 1
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalButton(
                                        onClick = { if (pageOffset - pageSize >= 0) loadPage(tab.type, pageOffset - pageSize, pageSize) },
                                        enabled = pageOffset > 0,
                                        modifier = Modifier.heightIn(min = 52.dp)
                                    ) { Text("上一页", style = MaterialTheme.typography.labelLarge) }
                                    Text(
                                        "第 $curPage / $totalPages 页 · 共 $pageTotal 项",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                    FilledTonalButton(
                                        onClick = { if (pageOffset + pageSize < pageTotal) loadPage(tab.type, pageOffset + pageSize, pageSize) },
                                        enabled = pageOffset + pageSize < pageTotal,
                                        modifier = Modifier.heightIn(min = 52.dp)
                                    ) { Text("下一页", style = MaterialTheme.typography.labelLarge) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 全屏播放 / 查看覆盖层
        selectedVideo?.let { v ->
            FullscreenVideo(
                backend = backend,
                item = v,
                onRequestDownload = onRequestDownload,
                onClose = { selectedVideo = null }
            )
        }
        selectedPhoto?.let { p ->
            FullscreenPhoto(
                backend = backend,
                item = p,
                onRequestDownload = onRequestDownload,
                onClose = { selectedPhoto = null }
            )
        }
        if (showSettings) {
            SettingsDialog(
                context = context,
                initialPageSize = pageSize,
                initialNameMode = nameDisplayMode,
                onPageSizeChange = { sz ->
                    pageSize = sz
                    pageOffset = 0
                    loadPage(tab.type, 0, sz)
                },
                onNameModeChange = { nameDisplayMode = it },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun MediaGridLoading() {
    // 骨架屏与真实网格同构（16:9 卡片 + 相同间距），加载完成时视觉不跳动
    Column(
        Modifier.fillMaxSize().padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                Card(
                    modifier = Modifier.weight(1f).aspectRatio(16f / 9f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {}
            }
        }
        CircularProgressIndicator(
            modifier = Modifier.padding(top = 28.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "正在读取 DVR 媒体…",
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun MediaEmptyState(title: String, subtitle: String, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Photo, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(52.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        FilledTonalButton(onClick = onRefresh, modifier = Modifier.padding(top = 12.dp).heightIn(min = 52.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text("刷新", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * 媒体库分类菜单：现代简约胶囊样式（选中实心主色、未选中描边浅底），
 * 替代传统下划线 TabRow；横向可滚动以兼容窄屏，点击无深色 ripple 铺底反馈。
 */
@Composable
private fun MediaCategoryPills(current: MediaTab, onSelect: (MediaTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MediaTab.values().filter { it != MediaTab.LIVE }.forEach { t ->
            val selected = current == t
            val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(container)
                    .border(
                        1.dp,
                        if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(50)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(t) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (t) {
                    MediaTab.VIDEO -> Icon(Icons.Filled.Videocam, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                    MediaTab.EVENT -> Icon(Icons.Filled.Warning, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                    MediaTab.PHOTO -> Icon(Icons.Filled.Photo, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                    MediaTab.LIVE -> Unit
                }
                Text(t.label, color = content, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** 日期分栏标题：左侧强调条 + 日期（含星期）+ 该栏数量。 */
@Composable
private fun MediaDateHeader(group: MediaDateGroup) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 24.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Text(
            group.label,
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "${group.items.size} 项",
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MediaTile(
    backend: DvrBackend?,
    item: MediaItem,
    isEvent: Boolean,
    nameDisplayMode: MediaNameDisplayMode,
    onOpen: () -> Unit,
    onRequestDownload: (DvrBackend?, MediaItem) -> Unit
) {
    val isVideo = item.type != "photo"
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                MediaThumb(backend = backend, item = item)
                // 视频中央播放图标；照片不显示
                if (isVideo) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier
                                .background(Color(0x55000000), RoundedCornerShape(50))
                                .padding(10.dp)
                        )
                    }
                }
                // 紧急录像角标
                if (isEvent) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(8.dp)
                            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text("紧急", color = MaterialTheme.colorScheme.onTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                // 文件大小角标（DVR 索引页解析得到；解析不到时不显示）
                if (item.size > 0L) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(7.dp))
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            formatBytes(item.size),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                // 列表项独立下载按钮：点击弹出系统位置选择器后交给系统下载器，与全屏播放页相互独立
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    TileDownloadButton(backend = backend, item = item, onRequestDownload = { onRequestDownload(backend, item) })
                }
            }
            Text(
                formatMediaName(item.date, item.name, nameDisplayMode),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (nameDisplayMode == MediaNameDisplayMode.FRIENDLY_AND_ORIGINAL) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * 列表项独立下载按钮：点击直接发起下载（系统下载器优先 / 内置回退）。
 * 进度来自 DownloadTracker（Compose State）：进行中显示百分比或不确定圈，
 * 下载中再次点击 = 取消下载；完成显示对勾、失败显示警示，约 4 秒后自动复位可再次下载。
 */
@Composable
private fun TileDownloadButton(
    backend: DvrBackend?,
    item: MediaItem,
    onRequestDownload: () -> Unit
) {
    val context = LocalContext.current
    val progressState = DownloadTracker.progress[item.path]
    val busy = progressState != null && !progressState.done && !progressState.failed
    val done = progressState?.done == true
    val failed = progressState?.failed == true

    FilledIconButton(
        onClick = {
            if (busy) {
                // 下载中点击 = 取消（系统任务 remove / 内置任务 cancel + 清理半成品）
                DownloadTracker.cancel(item.path)
                Toast.makeText(context, "已取消下载", Toast.LENGTH_SHORT).show()
                return@FilledIconButton
            }
            if (done) return@FilledIconButton
            if (backend?.isReady() != true) {
                Toast.makeText(context, "DVR 未连接，无法下载", Toast.LENGTH_SHORT).show()
            } else {
                onRequestDownload()
            }
        },
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = when {
                failed -> MaterialTheme.colorScheme.errorContainer
                busy || done -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when {
                failed -> MaterialTheme.colorScheme.onErrorContainer
                busy || done -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.primary
            }
        )
    ) {
        when {
            done -> Icon(Icons.Filled.Check, contentDescription = "下载完成", modifier = Modifier.size(20.dp))
            failed -> Icon(Icons.Filled.Warning, contentDescription = "下载失败，点击重试", modifier = Modifier.size(20.dp))
            busy -> {
                val fraction = progressState?.fraction
                if (fraction == null) {
                    // 总大小未知：只给一个小圈表示在跑，不占位大转圈
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
            else -> Icon(Icons.Filled.Download, contentDescription = "下载", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MediaThumb(backend: DvrBackend?, item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnailCache = remember(context) { MediaThumbnailCache.get(context) }
    var bmp by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(item.path) { mutableStateOf(true) }
    LaunchedEffect(item.path, backend, thumbnailCache) {
        val b = backend
        val key = b?.directUrl(item.path).orEmpty()
        if (b == null || key.isEmpty()) { bmp = null; loading = false; return@LaunchedEffect }
        // 1) 磁盘缓存快路径：命中即秒出，不进队列
        val cached = thumbnailCache.get(key)?.let { decodeSampledFile(it, 360) }
        if (cached != null) { bmp = cached; loading = false; return@LaunchedEffect }
        loading = true
        // 2) 进优先级队列：最新（列表靠前）条目 priority 更小，优先于较旧/离屏条目生成。
        val task = ThumbScheduler.submit(priority = -mediaTimestamp(item.name)) {
            if (!cancelled) {
                val generated = runThumbnailGeneration(b, item, key, thumbnailCache)
                if (!cancelled) {
                    withContext(Dispatchers.Main.immediate) { bmp = generated; loading = false }
                }
            }
        }
        try {
            task.await()
        } catch (_: CancellationException) {
            // 滚出视野被取消：标记任务取消，避免 worker 仍在跑
            task.cancelNow()
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else if (loading) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.height(28.dp).padding(4.dp), strokeWidth = 2.dp)
        }
    } else {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("无预览", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * 缩略图优先级队列：多个 tile 同时需要生成时按 priority 调度——最新（列表靠前）条目优先生成，
 * 较旧/离屏条目排队等到空闲。复用 DVR 端缩略图（极小 GET）后单条成本很低，队列主要用于避免
 * 一次突发请求压垮 DVR 并发。任务取消通过 CompletableDeferred 传播，不泄漏。
 */
private object ThumbScheduler {
    private const val WORKERS = 4
    private val queue = PriorityBlockingQueue<ThumbTask>(31, Comparator { a, b ->
        val p = a.priority.compareTo(b.priority)
        if (p != 0) p else a.seq.compareTo(b.seq)
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Any()
    @Volatile private var started = false
    private val seq = AtomicLong(0)

    fun ensureStarted() {
        synchronized(startLock) {
            if (!started) {
                started = true
                repeat(WORKERS) { scope.launch { worker() } }
            }
        }
    }

    fun submit(priority: Long, block: suspend ThumbTask.() -> Unit): ThumbTask {
        ensureStarted()
        // 同优先级按提交序号 FIFO，避免饥饿
        val task = ThumbTask(priority, seq.getAndIncrement(), block)
        queue.put(task)
        return task
    }

    private suspend fun worker() {
        while (true) {
            val task = queue.take()
            try { task.execute() } catch (_: CancellationException) { /* 忽略 */ } catch (_: Exception) { }
            task.complete()
        }
    }
}

private class ThumbTask(val priority: Long, val seq: Long, val block: suspend ThumbTask.() -> Unit) {
    @Volatile var cancelled = false
    private val done = CompletableDeferred<Unit>()
    fun cancelNow() { cancelled = true; done.cancel() }
    suspend fun await() = done.await()
    fun complete() { if (!done.isCompleted) done.complete(Unit) }
    /** 以自身作为扩展接收者调用 block，避免外部访问 block 属性时接收者未绑定。 */
    suspend fun execute() { block(this) }
}

/** 生成单个媒体缩略图：优先 DVR 端直链（小 GET），失败回退客户端取帧/降采样，成功写回磁盘缓存。 */
private suspend fun runThumbnailGeneration(
    b: DvrBackend,
    item: MediaItem,
    key: String,
    thumbnailCache: ThumbnailDiskCache
): Bitmap? {
    val generated = b.dvrThumbnailUrl(item)
        ?.let { url -> b.fetchThumbnail(url) }
        ?.let { bytes -> decodeSampledBytes(bytes, 360) }
        ?: if (item.type == "photo") {
            loadSampledPhoto(b, item, thumbnailCache, 360)
        } else {
            extractVideoFrame(key)
        }
    if (generated != null) {
        val cacheFile = thumbnailCache.put(key) { output ->
            if (!generated.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                throw IOException("无法编码媒体缩略图")
            }
        }
        if (cacheFile == null) {
            AppLog.w("缩略图", "磁盘空间不足或缓存容量不足，未缓存：${item.name}")
        }
    }
    return generated
}

/**
 * 带上限的 HTTP 数据源：只为取首帧而拉取文件前若干字节（Range 请求），
 * 避免 MediaMetadataRetriever 对远程直链从头拉整段。Cherokee 原生支持 Range。
 * 封顶 maxBytes，超出即返回 EOF，绝不拉整段文件。
 */
private class BoundedHttpMediaSource(
    private val url: String,
    private val maxBytes: Long
) : MediaDataSource() {
    private val lock = Any()
    private var initialized = false
    private var data: ByteArray? = null
    private var fetched = 0L         // data 中已填充的字节数
    private var totalSize = -1L      // 真实文件大小（封顶 maxBytes 后的值）
    private var closed = false

    private fun init() {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            totalSize = runCatching { headContentLength() }.getOrDefault(-1L)
            val cap = if (totalSize > 0) min(totalSize, maxBytes) else maxBytes
            data = ByteArray(cap.toInt())
        }
    }

    override fun getSize(): Long {
        init()
        // 小文件（≤上限）如实返回真实大小；大文件（被封顶）返回 -1（未知），
        // 让 MediaMetadataRetriever 以 EOF 收尾，触发下方回退到直连 URL。
        return if (totalSize in 0..maxBytes) totalSize else -1L
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0) return -1
        init()
        ensure(position + size.toLong())
        synchronized(lock) {
            val d = data ?: return -1
            if (position >= fetched) return -1
            val n = min((fetched - position).toInt(), size)
            System.arraycopy(d, position.toInt(), buffer, offset, n)
            return n
        }
    }

    private fun ensure(need: Long) {
        synchronized(lock) {
            if (closed || need <= fetched) return
            val cap = (data?.size ?: 0).toLong()
            while (fetched < need && fetched < cap) {
                val end = min(fetched + CHUNK - 1, cap - 1)
                val chunk = httpRange(fetched, end) ?: break
                System.arraycopy(chunk, 0, data!!, fetched.toInt(), chunk.size)
                fetched += chunk.size
            }
        }
    }

    private fun httpRange(start: Long, endInclusive: Long): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$start-$endInclusive")
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", "DashcamAssistant")
        }
        return try {
            if (conn.responseCode !in 200..299 && conn.responseCode != 206) null
            else conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun headContentLength(): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 10_000
            readTimeout = 45_000
        }
        return try {
            if (conn.responseCode !in 200..299 && conn.responseCode != 206) -1 else conn.contentLengthLong
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; data = null }
    }

    companion object {
        private const val CHUNK = 512 * 1024
    }
}

/** 用 MediaMetadataRetriever 取视频首帧（客户端动态生成缩略图，不走 DVR 缩略图端点）。 */
private fun extractVideoFrame(url: String, maxBytes: Long = MediaCachePolicy.MAX_THUMBNAIL_FETCH_BYTES): Bitmap? {
    fun fromRetriever(get: (MediaMetadataRetriever) -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            get(retriever)
            // time=0 取不到（部分设备首帧黑屏或未就绪）时回退到 1s 处，提高缩略图成功率
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
    // 优先：按上限 Range 拉取取首帧（拉取量封顶，对 DVR 小服务器友好）；
    // 极端情况下（如 moov 在文件尾部导致前 8MB 取不到帧）回退到直连 URL（旧行为）。
    return fromRetriever { it.setDataSource(BoundedHttpMediaSource(url, maxBytes)) }
        ?: fromRetriever { it.setDataSource(url) }
}

/**
 * 生成照片缩略图时的临时目录最小可用空间兜底阈值：仅作最后防线，避免分区彻底写满时还硬试。
 * 注意：这里刻意不使用 [MediaCachePolicy.MIN_FREE_BYTES_FOR_CACHING]——那是给长期磁盘缓存预留的保守阈值，
 * 而缩略图临时文件下载完即删，只需单文件量级的余量即可，套用 32MB+上限 会在清缓存后误报“可用空间不足”。
 */
private const val TEMP_DIR_MIN_FREE_BYTES = 2L * 1024 * 1024

private suspend fun loadSampledPhoto(
    backend: DvrBackend,
    item: MediaItem,
    cache: ThumbnailDiskCache,
    maxDimension: Int,
    maxSourceBytes: Long = MediaCachePolicy.MAX_THUMBNAIL_SOURCE_BYTES
): Bitmap? = mediaPhotoTransferSemaphore.withPermit {
    withContext(Dispatchers.IO) {
        val usable = cache.temporaryDirectory.usableSpace
        if (usable < TEMP_DIR_MIN_FREE_BYTES) {
            throw IOException("临时目录可用空间过低（${usable / (1024 * 1024)}MB），无法生成照片缩略图")
        }
        // 实际只需下载“真实文件大小”量级的数据：用 item.size 估算下载上限（未知时回退 maxSourceBytes），
        // 并始终不超过 maxSourceBytes 这一安全硬上限，避免一次性拉取超大文件压垮 DVR 嵌入式小服务器。
        val downloadCap = if (item.size > 0L) {
            minOf(item.size + 8L * 1024 * 1024, maxSourceBytes)
        } else {
            maxSourceBytes
        }
        val temp = File.createTempFile("dashcam-thumb-", ".tmp", cache.temporaryDirectory)
        try {
            temp.outputStream().use { output ->
                backend.copyFileTo(
                    item.path,
                    LimitedOutputStream(output, downloadCap)
                )
            }
            decodeSampledFile(temp, maxDimension)
        } catch (e: IOException) {
            // 磁盘空间不足等真实 IO 错误：转译为可读信息交由 UI 展示，避免臆断“可用空间不足”
            throw IOException("图片读取失败：${e.message}")
        } finally {
            temp.delete()
        }
    }
}

private fun decodeSampledFile(file: File, maxDimension: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    val longer = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
    var sampleSize = 1
    while (longer / sampleSize > maxDimension) sampleSize *= 2
    opts.inSampleSize = sampleSize
    opts.inJustDecodeBounds = false
    return FileInputStream(file).use { BitmapFactory.decodeStream(it, null, opts) }
}

/** 与 [decodeSampledFile] 同理，但源是内存中的字节数组（DVR 端缩略图直链）。解码失败返回 null。 */
private fun decodeSampledBytes(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    val longer = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
    var sampleSize = 1
    while (longer / sampleSize > maxDimension) sampleSize *= 2
    opts.inSampleSize = sampleSize
    opts.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/** 直播（RTSP）单次会话内最多自动重连次数，超限后才交给用户手动重试。 */
private const val RTSP_AUTO_RETRY_LIMIT = 10
/** 自动重连延时区间：每次在该区间内随机取值（毫秒），避免固定间隔与 DVR 侧节奏同步。 */
private const val RTSP_AUTO_RETRY_MIN_MS = 2_000L
private const val RTSP_AUTO_RETRY_MAX_MS = 5_000L
/** 已建立会话但超过该时长仍无首帧，判定为服务端未真正推流。 */
private const val RTSP_WATCHDOG_MS = 15_000L

/**
 * 判断直播播放错误是否为“重连即可恢复”的瞬态错误。
 * DVR 侧常见问题：DESCRIBE 返回的 SDP 偶发缺少 a=fmtp（编码器 SPS/PPS 尚未就绪），
 * Media3 会抛 IllegalArgumentException；此外还有 SETUP/PLAY 超时、RTP 未推流等。
 */
private fun isTransientLiveError(e: PlaybackException): Boolean {
    // 源/IO/解析类错误（1000 未分类、2000 IO、3000 解析）在直播场景下重连即可能恢复；
    // 解码器类错误（4000+）不属于重连可修复范围，交给用户手动处理。
    if (e.errorCode in 1000..3999) return true
    var cause: Throwable? = e.cause
    var depth = 0
    while (cause != null && depth < 6) {
        val message = cause.message.orEmpty()
        if (message.contains("fmtp", ignoreCase = true) ||
            message.contains("sprop", ignoreCase = true) ||
            message.contains("DESCRIBE", ignoreCase = true) ||
            message.contains("SETUP", ignoreCase = true) ||
            message.contains("SDP", ignoreCase = true) ||
            message.contains("rtsp", ignoreCase = true)
        ) return true
        if (cause is java.io.IOException || cause is java.net.SocketException || cause is IllegalArgumentException) return true
        cause = cause.cause
        depth++
    }
    return false
}

@Composable
private fun Media3Player(
    url: String,
    rtsp: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    var attempt by remember(url) { mutableIntStateOf(0) }
    var error by remember(url, attempt) { mutableStateOf<String?>(null) }
    var loading by remember(url, attempt) { mutableStateOf(true) }
    var prepared by remember(url, attempt) { mutableStateOf(false) }
    var playing by remember(url, attempt) { mutableStateOf(false) }
    var positionMs by remember(url, attempt) { mutableStateOf(0L) }
    var durationMs by remember(url, attempt) { mutableStateOf(0L) }
    var bufferedPositionMs by remember(url, attempt) { mutableStateOf(0L) }
    var player by remember(url, attempt) { mutableStateOf<ExoPlayer?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var cacheWarning by remember(url, attempt) { mutableStateOf<String?>(null) }
    /** 后台自动重连中：UI 只显示“加载中…”，不暴露重试次数。 */
    var reconnecting by remember(url, attempt) { mutableStateOf(false) }
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    // 倍速档位与当前档（点播可选，直播固定 1x）；截图进行中标记
    var speedIndex by remember(url, attempt) { mutableStateOf(0) }
    var screenshotting by remember(url, attempt) { mutableStateOf(false) }

    DisposableEffect(url, rtsp, attempt) {
        var instance: ExoPlayer? = null
        var firstFrameRendered = false
        var retryRunnable: Runnable? = null
        var watchdogRunnable: Runnable? = null
        val currentAttempt = attempt

        // 直播源（RTSP）偶发握手失败：DVR 在编码器 SPS/PPS 未就绪时返回的 SDP 可能缺少 a=fmtp，
        // Media3 会直接抛 IllegalArgumentException。这类错误是瞬态的，重连即可恢复。
        // 重连在后台静默进行（随机 2~5s 延时，上限 10 次），前端只显示“加载中…”；
        // 达到上限仍未恢复时才显示错误提示 + 「重试」按钮，交由用户手动处理。
        val autoRetry: (String, PlaybackException?) -> Boolean = { reason, cause ->
            val next = currentAttempt + 1
            if (rtsp && next <= RTSP_AUTO_RETRY_LIMIT) {
                val delayMs = RTSP_AUTO_RETRY_MIN_MS +
                    (kotlin.random.Random.nextDouble() * (RTSP_AUTO_RETRY_MAX_MS - RTSP_AUTO_RETRY_MIN_MS)).toLong()
                retryRunnable = Runnable {
                    retryRunnable = null
                    if (attempt == currentAttempt && !firstFrameRendered) attempt = next
                }
                mainHandler.postDelayed(retryRunnable!!, delayMs)
                error = null
                reconnecting = true
                loading = true
                AppLog.w("直播", "RTSP 后台重连：${delayMs}ms 后第 $next/$RTSP_AUTO_RETRY_LIMIT 次（$reason）", cause)
                true
            } else {
                reconnecting = false
                false
            }
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                loading = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                prepared = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(playbackException: PlaybackException) {
                loading = false
                playing = false
                val message = "播放失败（${playbackException.errorCodeName}）：${playbackException.message ?: "请检查视频编码或设备连接"}"
                val retried = if (isTransientLiveError(playbackException)) {
                    autoRetry(playbackException.errorCodeName, playbackException)
                } else {
                    false
                }
                if (!retried) {
                    // 达到自动重连上限后才落到终态错误提示：给出次数与原因，并保留「重试」按钮
                    error = if (rtsp) {
                        "直播连接失败（已自动重试 $RTSP_AUTO_RETRY_LIMIT 次）：${playbackException.message ?: "请检查 DVR 是否正在录像"}"
                    } else {
                        message
                    }
                    AppLog.e("播放器", error ?: message, playbackException)
                }
            }
        }
        val analyticsListener = object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                firstFrameRendered = true
                watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                watchdogRunnable = null
                retryRunnable?.let { mainHandler.removeCallbacks(it) }
                retryRunnable = null
                if (rtsp) {
                    AppLog.i("直播", "Media3 RTSP 首帧已渲染")
                    reconnecting = false
                    if (error != null && error!!.contains("自动重连")) error = null
                }
            }
        }
        try {
            if (url.isBlank()) throw IOException("无效的播放地址")
            val loadControl = DefaultLoadControl.Builder()
                // 增大缓冲水位，减少弱网/低端设备上的频繁 rebuffer：
                // 最小缓冲 8s、最大缓冲 30s、起播缓冲 1.5s、重缓冲后起播 3s、目标缓冲 48MB
                .setBufferDurationsMs(8_000, 30_000, 1_500, 3_000)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val exoPlayer = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
            instance = exoPlayer
            exoPlayer.addListener(listener)
            exoPlayer.addAnalyticsListener(analyticsListener)
            val mediaItem = ExoMediaItem.fromUri(url)
            val mediaSource = when {
                rtsp -> RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(mediaItem)
                else -> {
                    val dataSource = MediaPlaybackDataSource.create(context) { warning ->
                        mainHandler.post { cacheWarning = warning }
                    }
                    cacheWarning = dataSource.warning
                    ProgressiveMediaSource.Factory(dataSource.factory)
                        .createMediaSource(mediaItem)
                }
            }
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
            player = exoPlayer
            if (rtsp && url.isNotBlank()) {
                // 看门狗：握手成功但迟迟不出画面（服务端未真正发流）时也要自动重连。
                watchdogRunnable = Runnable {
                    watchdogRunnable = null
                    if (firstFrameRendered) return@Runnable
                    AppLog.w("直播", "RTSP 已连接但 ${RTSP_WATCHDOG_MS}ms 内未收到画面")
                    if (!autoRetry("长时间未收到画面", null)) {
                        error = "直播流无画面，请检查 DVR 是否正在录像"
                    }
                }
                mainHandler.postDelayed(watchdogRunnable!!, RTSP_WATCHDOG_MS)
            }
        } catch (e: Exception) {
            error = e.message ?: "播放器初始化失败"
            loading = false
            AppLog.e("播放器", "Media3 初始化失败", e)
        }
        onDispose {
            retryRunnable?.let { mainHandler.removeCallbacks(it) }
            watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
            retryRunnable = null
            watchdogRunnable = null
            playerView?.player = null
            player = null
            instance?.let {
                it.removeListener(listener)
                it.removeAnalyticsListener(analyticsListener)
                it.release()
            }
        }
    }

    LaunchedEffect(url, player, prepared) {
        val activePlayer = player ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        while (true) {
            positionMs = activePlayer.currentPosition.coerceAtLeast(0L)
            durationMs = activePlayer.duration.takeIf { it > 0L } ?: 0L
            bufferedPositionMs = activePlayer.bufferedPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    playerView = this
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        if (reconnecting && error == null) {
            // 后台重连期间只提示“加载中…”，不暴露重试次数，避免对用户造成干扰
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(
                    "加载中…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 14.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        } else if (loading && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        cacheWarning?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .heightIn(min = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!rtsp) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatPlaybackTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    PlaybackProgressBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedPositionMs,
                        enabled = prepared && durationMs > 0 && error == null,
                        onValueChange = { positionMs = it },
                        onValueChangeFinished = {
                            runCatching { player?.seekTo(positionMs) }
                                .onFailure { error = it.message ?: "无法跳转到所选时间" }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(formatPlaybackTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!rtsp) {
                    // 快退 10 秒
                    IconButton(
                        onClick = {
                            val activePlayer = player ?: return@IconButton
                            runCatching { activePlayer.seekTo((activePlayer.currentPosition - 10_000L).coerceAtLeast(0L)) }
                        },
                        enabled = prepared && error == null && player != null
                    ) {
                        Icon(Icons.Filled.Replay10, contentDescription = "快退 10 秒", tint = Color.White)
                    }
                }
                // 播放 / 暂停（主控制，稍大）
                FilledIconButton(
                    onClick = {
                        val activePlayer = player ?: return@FilledIconButton
                        if (activePlayer.isPlaying) {
                            activePlayer.pause()
                        } else if (prepared) {
                            activePlayer.play()
                        }
                    },
                    enabled = prepared && error == null && player != null,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放"
                    )
                }
                if (!rtsp) {
                    // 快进 10 秒
                    IconButton(
                        onClick = {
                            val activePlayer = player ?: return@IconButton
                            runCatching { activePlayer.seekTo(activePlayer.currentPosition + 10_000L) }
                        },
                        enabled = prepared && error == null && player != null && durationMs > 0
                    ) {
                        Icon(Icons.Filled.Forward10, contentDescription = "快进 10 秒", tint = Color.White)
                    }
                    // 倍速循环切换
                    FilledTonalButton(
                        onClick = {
                            val activePlayer = player ?: return@FilledTonalButton
                            val next = (speedIndex + 1) % PLAYBACK_SPEEDS.size
                            speedIndex = next
                            runCatching { activePlayer.setPlaybackSpeed(PLAYBACK_SPEEDS[next]) }
                        },
                        enabled = prepared && error == null && player != null,
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White
                        )
                    ) {
                        Text("${PLAYBACK_SPEEDS[speedIndex]}x", style = MaterialTheme.typography.labelMedium)
                    }
                    // 截图：按当前播放位置取帧，保存到 Pictures/DashcamAssistant
                    IconButton(
                        onClick = {
                            val activePlayer = player ?: return@IconButton
                            if (screenshotting) return@IconButton
                            screenshotting = true
                            val position = activePlayer.currentPosition
                            val videoUrl = url
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    captureFrameToGallery(context, videoUrl, position)
                                }
                                screenshotting = false
                                Toast.makeText(
                                    context,
                                    if (ok != null) "截图已保存：$ok" else "截图失败，请重试",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = prepared && error == null && player != null
                    ) {
                        if (screenshotting) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = "截图", tint = Color.White)
                        }
                    }
                } else {
                    Text("直播", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (error != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    error!!,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                FilledTonalButton(
                    onClick = { attempt++ },
                    modifier = Modifier.padding(top = 12.dp).heightIn(min = 52.dp)
                ) { Text("重试", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** 播放器倍速档位（控制条上循环切换）。 */
private val PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)

/**
 * 按播放位置截取当前帧并保存到系统相册 Pictures/DashcamAssistant。
 * 返回保存的文件名；失败返回 null。用 MediaMetadataRetriever 直连 DVR HTTP 直链取帧。
 */
private fun captureFrameToGallery(context: android.content.Context, url: String, positionMs: Long): String? {
    if (url.isBlank()) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(url, HashMap())
        val positionUs = positionMs.coerceAtLeast(0L) * 1000L
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 720)
        } else {
            retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } ?: return null
        val resolver = context.contentResolver
        val name = "截图_%s.jpg".format(
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
        )
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/DashcamAssistant"
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "DashcamAssistant"
                )
                if (!dir.exists() && !dir.mkdirs()) return null
                put(android.provider.MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
            }
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    throw IOException("截图写入失败")
                }
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            name
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    } catch (e: Exception) {
        AppLog.e("截图", "截取当前帧失败：${e.message}")
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * 播放进度条：在原生 Slider 基础上叠加「已缓冲」区段，并用主题色区分已播/已缓冲/未播三段，
 * 圆角细条 + 白色手柄，整体走现代简约风。可拖动跳转，松手时回调 onValueChangeFinished。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlaybackProgressBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    enabled: Boolean,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = durationMs.coerceAtLeast(1L)
    val safePos = positionMs.coerceIn(0L, duration)
    val progress = (safePos.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (bufferedMs.coerceIn(0L, duration).toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = safePos.toFloat(),
        onValueChange = { onValueChange(it.toLong()) },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 0f..duration.toFloat(),
        enabled = enabled,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            disabledThumbColor = Color.White.copy(alpha = 0.5f),
            disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            disabledInactiveTrackColor = Color.White.copy(alpha = 0.15f)
        ),
        // 圆点手柄：显式 14dp 圆形（覆盖默认大拇指），更符合"圆点滑块"的观感
        thumb = {
            Box(
                Modifier
                    .size(14.dp)
                    .background(Color.White, CircleShape)
            )
        },
        track = { _ ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(buffered)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                )
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
            }
        }
    )
}

@Composable
private fun FullscreenVideo(
    backend: DvrBackend?,
    item: MediaItem,
    onRequestDownload: (DvrBackend?, MediaItem) -> Unit,
    onClose: () -> Unit
) {
    // ExoPlayer uses a bounded disk cache and streams the DVR HTTP resource.
    val connected = backend?.isReady() == true
    val url = if (connected) (backend?.directUrl(item.path) ?: "") else ""
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (url.isNotEmpty()) {
            Media3Player(url = url, modifier = Modifier.fillMaxSize())
        } else {
            CenteredText("DVR 未连接")
        }
        // 顶部贯穿半透明操作栏：关闭、文件名、下载/进度/取消 统一放在同一区域
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledIconButton(
                onClick = onClose,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(22.dp))
            }
            Text(
                item.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            MediaSaveControls(backend, item, onRequestDownload)
        }
    }
}

@Composable
private fun FullscreenPhoto(
    backend: DvrBackend?,
    item: MediaItem,
    onRequestDownload: (DvrBackend?, MediaItem) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailCache = remember(context) { MediaThumbnailCache.get(context) }
    var bmp by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(item.path) { mutableStateOf(true) }
    var loadError by remember(item.path) { mutableStateOf("") }
    LaunchedEffect(item.path, backend) {
        val b = backend
        try {
            bmp = if (b == null) null else loadSampledPhoto(
                b,
                item,
                thumbnailCache,
                MediaCachePolicy.FULL_PHOTO_MAX_DIMENSION,
                MediaCachePolicy.MAX_FULL_PHOTO_SOURCE_BYTES
            )
            if (b == null) loadError = "DVR 未连接"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "图片读取失败"
        } finally {
            loading = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bmp != null) {
            Image(bitmap = bmp!!.asImageBitmap(), contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Text(
                if (loadError.isEmpty()) "图片解码失败，请检查文件格式" else "图片加载失败：$loadError",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // 与全屏视频一致的顶部贯穿半透明操作栏：关闭、文件名、下载/进度/取消
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledIconButton(
                onClick = onClose,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(22.dp))
            }
            Text(
                item.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            MediaSaveControls(backend, item, onRequestDownload)
        }
    }
}

/**
 * 全屏页保存控件（内联透明样式，置于顶部半透明操作栏内）：
 * 进度统一来自 DownloadTracker；下载中提供「取消」，失败提供「重试」。
 */
@Composable
private fun MediaSaveControls(
    backend: DvrBackend?,
    item: MediaItem,
    onRequestDownload: (DvrBackend?, MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 进度统一来自 DownloadTracker：与缩略图下载按钮同一数据源
    val progressState = DownloadTracker.progress[item.path]
    val busy = progressState != null && !progressState.done && !progressState.failed
    val done = progressState?.done == true
    val failed = progressState?.failed == true
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (busy || done || failed) {
            Text(
                when {
                    busy -> {
                        val fraction = progressState?.fraction
                        if (fraction == null) "下载中…" else "下载中 ${(fraction * 100).toInt()}%"
                    }
                    done -> "已保存"
                    else -> "下载失败"
                },
                color = when {
                    failed -> Color(0xFFFF8A80)
                    done -> DashcamConnectedDark
                    else -> Color.White
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
            FilledTonalButton(
                onClick = {
                    DownloadTracker.cancel(item.path)
                    Toast.makeText(context, "已取消下载", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White
                )
            ) { Text("取消", style = MaterialTheme.typography.labelLarge) }
        } else {
            FilledTonalButton(
                onClick = { if (backend?.isReady() == true) onRequestDownload(backend, item) },
                enabled = backend?.isReady() == true,
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    if (failed) Icons.Filled.Warning else Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    if (failed) "重试" else if (done) "再下载" else "下载",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(value / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
        else -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
private fun LiveTab(backend: DvrBackend?, modifier: Modifier = Modifier) {
    val connected = backend?.isReady() == true
    val rtsp = if (connected) backend?.liveUrl().orEmpty() else ""

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (connected && rtsp.startsWith("rtsp://")) {
            Media3Player(url = rtsp, rtsp = true, modifier = Modifier.fillMaxSize())
            Text(
                "实时预览（RTSP/TCP）",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val message = if (!connected) "DVR 未连接，无法预览直播" else "DVR 返回的 RTSP 地址无效"
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
