package com.dashcam.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import com.dashcam.assistant.bridge.DvrBackend
import com.dashcam.assistant.bridge.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import java.io.File
import java.io.FileInputStream
import java.io.IOException

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

private val mediaThumbSemaphore = Semaphore(2)
private val mediaPhotoTransferSemaphore = Semaphore(1)

private object MediaThumbnailCache {
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
    var nameModeMenu by remember { mutableStateOf(false) }
    var pageSizeMenu by remember { mutableStateOf(false) }
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
    var pageSize by remember { mutableStateOf(10) }
    var pageTotal by remember { mutableStateOf(0) }

    /** 加载指定页（默认不刷新缓存；refresh=true 时强制重新扫描 DVR）。 */
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
            loading = true
            loadError = ""
            try {
                if (refresh) b.invalidateCache(type)
                val page = withContext(Dispatchers.IO) { b.listMediaPaged(type, offset, limit) }
                if (generation == loadGeneration && tab.type == type && backend === b) {
                    mediaItems = page.items
                    pageTotal = page.total
                    pageOffset = page.offset
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration && tab.type == type && backend === b) {
                    mediaItems = emptyList()
                    pageTotal = 0
                    loadError = e.message ?: "读取 DVR 媒体失败"
                }
            } finally {
                if (generation == loadGeneration) {
                    loading = false
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
                Button(
                    onClick = { AppLog.i("车机", "返回主页"); onBack() },
                    modifier = Modifier.heightIn(min = 56.dp)
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
            }
            // 实时预览由首页独立入口进入（route="live"），媒体库内不再放入口按钮，
            // 预览页左上角的「返回」直接回主页；分类 Tab 仅在媒体库三个分类下显示。
            if (tab != MediaTab.LIVE) {
                // 小屏用可滚动 TabRow，避免三个带图标的标签被挤在一行；大屏保持等分更整齐
                val tabContent: @Composable () -> Unit = {
                    MediaTab.values().filter { it != MediaTab.LIVE }.forEach { t ->
                        Tab(
                            selected = tab == t,
                            onClick = {
                                AppLog.i("车机", "切换分类：${t.label}")
                                tab = t
                                pageOffset = 0
                            },
                            text = { Text(t.label, style = MaterialTheme.typography.labelLarge) },
                            icon = {
                                when (t) {
                                    MediaTab.VIDEO -> Icon(Icons.Filled.Videocam, contentDescription = null)
                                    MediaTab.EVENT -> Icon(Icons.Filled.Warning, contentDescription = null)
                                    MediaTab.PHOTO -> Icon(Icons.Filled.Photo, contentDescription = null)
                                    MediaTab.LIVE -> Unit
                                }
                            }
                        )
                    }
                }
                if (metrics.scrollableTabs) {
                    ScrollableTabRow(
                        selectedTabIndex = tab.ordinal,
                        containerColor = MaterialTheme.colorScheme.background
                    ) { tabContent() }
                } else {
                    TabRow(
                        selectedTabIndex = tab.ordinal,
                        containerColor = MaterialTheme.colorScheme.background
                    ) { tabContent() }
                }
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
                            Button(onClick = { loadPage(tab.type, pageOffset, pageSize, refresh = true) }, modifier = Modifier.padding(top = 12.dp).heightIn(min = 56.dp)) {
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
                                            }
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
                                    OutlinedButton(
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
                                    OutlinedButton(
                                        onClick = { if (pageOffset + pageSize < pageTotal) loadPage(tab.type, pageOffset + pageSize, pageSize) },
                                        enabled = pageOffset + pageSize < pageTotal,
                                        modifier = Modifier.heightIn(min = 52.dp)
                                    ) { Text("下一页", style = MaterialTheme.typography.labelLarge) }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "每页",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Box {
                                        OutlinedButton(
                                            onClick = { pageSizeMenu = true },
                                            modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 76.dp)
                                        ) { Text("$pageSize 项", style = MaterialTheme.typography.labelLarge) }
                                        DropdownMenu(expanded = pageSizeMenu, onDismissRequest = { pageSizeMenu = false }) {
                                            listOf(10, 15, 20).forEach { sz ->
                                                DropdownMenuItem(
                                                    text = { Text("每页 $sz 项") },
                                                    onClick = {
                                                        pageSizeMenu = false
                                                        if (pageSize != sz) {
                                                            pageSize = sz
                                                            pageOffset = 0
                                                            loadPage(tab.type, 0, sz)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        "文件名",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Box {
                                        OutlinedButton(
                                            onClick = { nameModeMenu = true },
                                            modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 104.dp)
                                        ) { Text(nameDisplayMode.label, style = MaterialTheme.typography.labelLarge) }
                                        DropdownMenu(expanded = nameModeMenu, onDismissRequest = { nameModeMenu = false }) {
                                            MediaNameDisplayMode.entries.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode.label) },
                                                    onClick = {
                                                        MediaNameDisplayPreference.save(context, mode)
                                                        nameDisplayMode = mode
                                                        nameModeMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 全屏播放 / 查看覆盖层
        selectedVideo?.let { v ->
            FullscreenVideo(backend = backend, item = v, onClose = { selectedVideo = null })
        }
        selectedPhoto?.let { p ->
            FullscreenPhoto(backend = backend, item = p, onClose = { selectedPhoto = null })
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
        OutlinedButton(onClick = onRefresh, modifier = Modifier.padding(top = 12.dp).heightIn(min = 56.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text("刷新", modifier = Modifier.padding(start = 8.dp))
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
    onOpen: () -> Unit
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

@Composable
private fun MediaThumb(backend: DvrBackend?, item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnailCache = remember(context) { MediaThumbnailCache.get(context) }
    var bmp by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(item.path) { mutableStateOf(true) }
    LaunchedEffect(item.path, backend, thumbnailCache) {
        val b = backend
        val key = b?.directUrl(item.path).orEmpty()
        loading = true
        bmp = if (b == null || key.isEmpty()) null else {
            try {
                mediaThumbSemaphore.withPermit {
                    withContext(Dispatchers.IO) {
                        val cached = thumbnailCache.get(key)?.let { decodeSampledFile(it, 360) }
                        cached ?: run {
                            val generated = if (item.type == "photo") {
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
                            generated
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("缩略图", "生成缩略图失败：${item.name}，${e.message}")
                null
            }
        }
        loading = false
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

/** 用 MediaMetadataRetriever 取视频首帧（客户端动态生成缩略图，不走 DVR 缩略图端点）。 */
private fun extractVideoFrame(url: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(url)
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private suspend fun loadSampledPhoto(
    backend: DvrBackend,
    item: MediaItem,
    cache: ThumbnailDiskCache,
    maxDimension: Int,
    maxSourceBytes: Long = MediaCachePolicy.MAX_THUMBNAIL_SOURCE_BYTES
): Bitmap? = mediaPhotoTransferSemaphore.withPermit {
    withContext(Dispatchers.IO) {
        val availableBytes = cache.temporaryDirectory.usableSpace
        if (availableBytes < MediaCachePolicy.MIN_FREE_BYTES_FOR_CACHING + maxSourceBytes) {
            throw IOException("可用空间不足，无法生成照片缩略图")
        }
        val temp = File.createTempFile("dashcam-thumb-", ".tmp", cache.temporaryDirectory)
        try {
            temp.outputStream().use { output ->
                backend.copyFileTo(
                    item.path,
                    LimitedOutputStream(output, maxSourceBytes)
                )
            }
            decodeSampledFile(temp, maxDimension)
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
    var player by remember(url, attempt) { mutableStateOf<ExoPlayer?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var cacheWarning by remember(url, attempt) { mutableStateOf<String?>(null) }
    /** 后台自动重连中：UI 只显示“加载中…”，不暴露重试次数。 */
    var reconnecting by remember(url, attempt) { mutableStateOf(false) }
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

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
                .setBufferDurationsMs(2_500, 10_000, 750, 1_500)
                .setTargetBufferBytes(16 * 1024 * 1024)
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
        Row(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val activePlayer = player ?: return@IconButton
                    if (activePlayer.isPlaying) {
                        activePlayer.pause()
                    } else if (prepared) {
                        activePlayer.play()
                    }
                },
                enabled = prepared && error == null && player != null
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White
                )
            }
            if (!rtsp) {
                Text(formatPlaybackTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                    onValueChange = { positionMs = it.toLong() },
                    onValueChangeFinished = {
                        runCatching { player?.seekTo(positionMs) }
                            .onFailure { error = it.message ?: "无法跳转到所选时间" }
                    },
                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    enabled = prepared && durationMs > 0 && error == null,
                    modifier = Modifier.weight(1f)
                )
                Text(formatPlaybackTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            } else {
                Text("直播", color = Color.White, style = MaterialTheme.typography.labelSmall)
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
                Button(
                    onClick = { attempt++ },
                    modifier = Modifier.padding(top = 12.dp).heightIn(min = 56.dp)
                ) { Text("重试", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun FullscreenVideo(backend: DvrBackend?, item: MediaItem, onClose: () -> Unit) {
    // ExoPlayer uses a bounded disk cache and streams the DVR HTTP resource.
    val connected = backend?.isReady() == true
    val url = if (connected) (backend?.directUrl(item.path) ?: "") else ""
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (url.isNotEmpty()) {
            Media3Player(url = url, modifier = Modifier.fillMaxSize())
        } else {
            CenteredText("DVR 未连接")
        }
        Button(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .heightIn(min = 52.dp)
        ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
        MediaSaveControls(
            backend,
            item,
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        )
        // 文件名叠在视频画面上：加半透明底衬，避免浅色画面下白字看不清
        Text(
            item.name,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(12.dp)
                .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FullscreenPhoto(backend: DvrBackend?, item: MediaItem, onClose: () -> Unit) {
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
        Button(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .heightIn(min = 52.dp)
        ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
        MediaSaveControls(
            backend,
            item,
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        )
    }
}

@Composable
private fun MediaSaveControls(backend: DvrBackend?, item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveState by remember(item.path) { mutableStateOf(MediaSaveState()) }
    var saveJob by remember(item.path) { mutableStateOf<Job?>(null) }

    val beginSave: () -> Unit = {
        val b = backend
        if (b?.isReady() != true) {
            saveState = saveState.copy(phase = MediaSavePhase.FAILED, error = "DVR 未连接")
        } else if (saveState.phase != MediaSavePhase.COPYING) {
            saveState = MediaSaveState().begin()
            saveJob = scope.launch {
                var lastProgressAt = 0L
                try {
                    saveMediaToLibrary(context, b, item) { copied, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= 250L || (total != null && copied >= total)) {
                            lastProgressAt = now
                            withContext(Dispatchers.Main.immediate) {
                                saveState = saveState.progress(copied, total)
                            }
                        }
                    }
                    saveState = saveState.succeed()
                    AppLog.i("媒体保存", "已将媒体保存到系统媒体库：${item.name}")
                } catch (e: CancellationException) {
                    saveState = saveState.cancel()
                    throw e
                } catch (e: Exception) {
                    saveState = saveState.fail(e.message ?: "保存失败")
                    AppLog.w("媒体保存", "保存失败：${item.name}，${saveState.error}")
                } finally {
                    saveJob = null
                }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginSave() else {
            saveState = MediaSaveState().fail("需要存储权限才能保存媒体")
        }
    }

    // 该面板叠在全屏黑色画面上，因此限定宽度（否则内部 LinearProgressIndicator 的
    // fillMaxWidth 会把面板撑到整屏宽），并统一用半透明深色底衬保证任意主题下可读。
    Column(
        modifier
            .widthIn(max = 240.dp)
            .background(Color(0xAA000000), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.End
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saveState.phase == MediaSavePhase.SAVED) {
                // 面板背景恒为深色，用亮绿；不用 Color.Green（浅色主题下对比度不足且非主题色）
                Text(
                    "已保存到媒体库",
                    color = DashcamConnectedDark,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            if (saveState.phase == MediaSavePhase.COPYING) {
                Button(
                    onClick = {
                        saveState = saveState.cancel()
                        saveJob?.cancel()
                    },
                    modifier = Modifier.heightIn(min = 52.dp)
                ) { Text("取消保存", style = MaterialTheme.typography.labelLarge) }
            } else {
                Button(
                    modifier = Modifier.heightIn(min = 52.dp),
                    onClick = {
                        saveState = MediaSaveState()
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            beginSave()
                        }
                    }
                ) { Text("保存", style = MaterialTheme.typography.labelLarge) }
            }
        }
        if (saveState.phase == MediaSavePhase.COPYING) {
            Text(
                if (saveState.totalBytes != null && saveState.totalBytes!! > 0) {
                    "已传输 ${formatBytes(saveState.copiedBytes)} / ${formatBytes(saveState.totalBytes!!)}"
                } else if (saveState.copiedBytes > 0) {
                    "已传输 ${formatBytes(saveState.copiedBytes)}"
                } else {
                    "正在准备保存…"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f)
            )
        }
        if (saveState.phase == MediaSavePhase.CANCELLED) {
            Text("保存已取消", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        if (saveState.error.isNotEmpty()) {
            // 面板在黑色画面上，用亮红而非 colorScheme.error（浅色主题下 error 是暗红，黑底看不清）
            Text(saveState.error, color = DashcamError, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
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
