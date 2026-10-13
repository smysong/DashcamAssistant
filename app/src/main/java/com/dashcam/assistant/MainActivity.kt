package com.dashcam.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dashcam.assistant.AppLog
import com.dashcam.assistant.bridge.BridgeService
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 车机屏主界面。
 * - 自动横/竖屏：依赖 AndroidManifest 的 screenOrientation="fullSensor" + configChanges，Compose 按窗口约束重排。
 * - 日间/夜间：**不再自处理 uiMode**（Manifest 的 configChanges 已去掉 uiMode），深浅色切换由系统重建 Activity，
 *   这样 values-night 的主题属性（statusBarColor / windowLightStatusBar 等）才能在窗口创建时真正生效，
 *   车机系统顶部状态栏与底部 Dock 才会跟随变深；当前页面路由用 rememberSaveable 保留，不会被弹回首页。
 *   另外在 onCreate/onConfigurationChanged/onResume 再用 applySystemBarsToActivity 命令式同步一次，
 *   覆盖车机 SystemUI 在窗口重建或重新获得焦点后恢复默认配色的情况。
 * - 展示手机扫码连接地址（二维码）、DVR 连接状态、重连按钮。
 */
class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * 深色模式状态。Manifest 已不再自处理 uiMode，深浅色切换会重建 Activity（onCreate 重新取值），
     * 这里保留 onConfigurationChanged 同步是为了兜住不重建也能收到的配置变化（如部分车机的自定义主题广播）。
     */
    private var darkTheme by mutableStateOf(false)

    private fun isNightModeActive(config: android.content.res.Configuration): Boolean =
        (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        darkTheme = isNightModeActive(newConfig)
        applySystemBarsToActivity(this, darkTheme)
    }

    override fun onResume() {
        super.onResume()
        // 车机 SystemUI 可能在窗口重新获得焦点后恢复自己的默认配色，这里再同步一次
        applySystemBarsToActivity(this, darkTheme)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkTheme = isNightModeActive(resources.configuration)
        applySystemBarsToActivity(this, darkTheme)

        // 启动后台桥接服务（前台服务，承载 UDP 发现 + amba + HTTP）
        val svc = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            DashcamAssistantTheme(darkTheme = darkTheme) {
                val context = LocalContext.current
                // 启动后后台自动检查更新（受开关与「本次会话抑制」约束，单次启动仅执行一次，
                // 规避深浅色切换重建 Activity 后重复弹窗）
                LaunchedEffect(Unit) { UpdateManager.maybeAutoCheck(context) }

                // rememberSaveable：深色/浅色切换会重建 Activity（见下方说明），
                // 保存当前路由可避免重建后被弹回首页。
                var route by rememberSaveable { mutableStateOf("home") }
                when (route) {
                    "home" -> MainScreen(
                        onManage = { route = "browser" },
                        onLive = { route = "live" }
                    )
                    else -> MediaBrowserScreen(
                        onBack = { route = "home" },
                        initialTab = if (route == "live") MediaTab.LIVE else MediaTab.VIDEO
                    )
                }

                // 全局升级提示：手动「检查更新」与启动后「后台自动检查」共用同一弹窗
                val pendingUpdate by UpdateManager.pendingUpdateState
                pendingUpdate?.let { latest ->
                    UpdateDialog(
                        latest = latest,
                        onLater = { UpdateManager.onLater() },
                        onDismiss = { UpdateManager.onDismiss() }
                    )
                }
            }
        }
    }

    @Composable
    private fun MainScreen(onManage: () -> Unit, onLive: () -> Unit) {
        val status by BridgeState.status.collectAsState()
        // 是否有新版本待升级（与升级弹窗共用同一状态），用于设置图标与版本区的小圆点提示
        val hasUpdate by UpdateManager.pendingUpdateState
        // QR only exposes the paired bridge URL after the service has created its session token.
        val bridgeUrl = status.bridgeUrl
        var qr by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(bridgeUrl) { qr = if (bridgeUrl.isNotEmpty()) generateQr(bridgeUrl) else null }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val windowClass = dashcamWindowClass()
                val isCar = windowClass == DashcamWindowClass.EXPANDED
                var showQrDialog by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                val metrics = dashcamLayoutMetrics(windowClass)
                // 捕获到局部变量，供深层嵌套的 isCar 布局块使用（避免隐式接收者歧义）
                val screenMaxHeight = maxHeight
                val compact = windowClass == DashcamWindowClass.COMPACT
                val titleStyle = when (windowClass) {
                    DashcamWindowClass.COMPACT -> MaterialTheme.typography.titleMedium
                    DashcamWindowClass.MEDIUM -> MaterialTheme.typography.headlineMedium
                    else -> MaterialTheme.typography.headlineLarge
                }
                val subtitleStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge
                val homeSpacing = if (compact) 12.dp else 20.dp
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = metrics.homeHorizontalPadding,
                            vertical = metrics.homeVerticalPadding
                        ),
                    verticalArrangement = Arrangement.spacedBy(homeSpacing)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("行车记录仪助手", style = titleStyle)
                            Text("设备状态与媒体访问", color = MaterialTheme.colorScheme.onSurfaceVariant, style = subtitleStyle)
                        }
                        BadgedBox(
                            badge = { if (hasUpdate != null) Badge() }
                        ) {
                            IconButton(onClick = { showSettings = true }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ConnectionCard(status = status, compact = !isCar, onRescan = {
                        AppLog.i("车机", "触发重新扫描")
                        BridgeState.onReconnect?.invoke()
                    })
                    if (isCar) {
                        // 注意：不能用 IntrinsicSize.Min —— 它会向子节点发起固有尺寸测量，
                        // 而 PairingCard 内的 BoxWithConstraints(SubcomposeLayout) 不支持固有测量，
                        // 会导致启动即 IllegalStateException 崩溃。改为用确定高度让两侧 fillMaxHeight 等高对齐。
                        val carRowHeight = minOf(screenMaxHeight * 0.5f, 300.dp).coerceAtLeast(240.dp)
                        Row(
                            Modifier.fillMaxWidth().height(carRowHeight),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            PairingCard(qr, bridgeUrl, Modifier.weight(1.1f).fillMaxHeight())
                            Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                HomeActionCard(
                                    title = "媒体库",
                                    description = "浏览录像、紧急录像与照片",
                                    icon = Icons.Filled.Storage,
                                    modifier = Modifier.weight(1f),
                                    onClick = { AppLog.i("车机", "打开媒体管理"); onManage() }
                                )
                                HomeActionCard(
                                    title = "实时预览",
                                    description = "查看当前 DVR 直播画面",
                                    icon = Icons.Filled.LiveTv,
                                    modifier = Modifier.weight(1f),
                                    onClick = onLive
                                )
                            }
                        }
                    } else {
                        // 手机 / 平板竖屏：三个入口统一为全宽横排卡片、纵向排列，扫码连接放最后；
                        // 默认不展示二维码大图，点击「扫码连接」卡片弹出二维码对话框。
                        // 车机（大屏横屏）仍保留原双栏 + 内联二维码布局（见 isCar 分支）。
                        HomeActionCard(
                            title = "媒体库",
                            description = "浏览录像、紧急录像与照片",
                            icon = Icons.Filled.Storage,
                            compact = compact,
                            onClick = { AppLog.i("车机", "打开媒体管理"); onManage() }
                        )
                        HomeActionCard(
                            title = "实时预览",
                            description = "查看当前 DVR 直播画面",
                            icon = Icons.Filled.LiveTv,
                            compact = compact,
                            onClick = onLive
                        )
                        HomeActionCard(
                            title = "扫码连接",
                            description = "点击显示手机连接二维码",
                            icon = Icons.Filled.QrCode,
                            compact = compact,
                            onClick = { showQrDialog = true }
                        )
                    }
                    // 底部居中的版权与版本信息：版本号由 server_build.py 每次构建自增
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "©2026 Smysong. All Rights Reserved.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "行车记录仪助手 | Version：${BuildConfig.VERSION_NAME}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center
                            )
                            if (hasUpdate != null) {
                                Box(
                                    Modifier
                                        .padding(start = 6.dp)
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.error, CircleShape)
                                )
                            }
                        }
                    }
                }
                if (showQrDialog) {
                    QrDialog(bitmap = qr, bridgeUrl = bridgeUrl, onDismiss = { showQrDialog = false })
                }
                if (showSettings) {
                    val ctx = LocalContext.current
                    SettingsDialog(
                        context = ctx,
                        initialPageSize = PageSizePreference.load(ctx),
                        initialNameMode = MediaNameDisplayPreference.load(ctx),
                        onPageSizeChange = { },
                        onNameModeChange = { },
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }

    @Composable
    private fun QrDialog(bitmap: Bitmap?, bridgeUrl: String, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("手机扫码连接", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        // 二维码必须白底黑码才能被扫描，不跟随主题；加描边避免浅色主题下与卡片融合
                        modifier = Modifier
                            .size(220.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    ) {
                        if (bridgeUrl.isEmpty()) {
                            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                                Text("等待桥接服务", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            }
                        } else if (bitmap != null) {
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "扫码连接手机网页", modifier = Modifier.padding(12.dp))
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Text(
                        "在手机浏览器中打开录像与照片媒体库",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "二维码仅在本次服务运行期间有效",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        )
    }

    @Composable
    private fun ConnectionCard(status: DvrStatusUi, compact: Boolean = false, onRescan: () -> Unit) {
        val statusColor = when {
            status.connected -> dashcamSuccessColor()
            status.error.isNotEmpty() -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.tertiary
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(if (compact) 16.dp else 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = statusColor, modifier = Modifier.size(if (compact) 26.dp else 30.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            status.connected -> "已连接 DVR"
                            status.discovered -> "已发现 DVR"
                            status.error.isNotEmpty() -> "连接遇到问题"
                            else -> "正在搜索附近设备"
                        },
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                    )
                    Text(
                        when {
                            status.connected -> status.dvrHost
                            status.discovered -> "设备地址：${status.dvrHost}"
                            status.error.isNotEmpty() -> status.error
                            status.scanInfo.isNotEmpty() -> status.scanInfo
                            else -> "请确认车机与记录仪处于同一可访问网络"
                        },
                        color = if (status.error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
                    )
                }
                if (compact) {
                    // 手机端：重新扫描缩为纯图标按钮，避免挤压状态文字造成难看换行
                    IconButton(onClick = onRescan, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "重新扫描",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    FilledTonalButton(onClick = onRescan, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("重新扫描", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    @Composable
    private fun PairingCard(bitmap: Bitmap?, bridgeUrl: String, modifier: Modifier = Modifier) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder()
        ) {
            // fillMaxSize + 居中：卡片被拉伸到与右侧等高时，内容在卡片内垂直居中
            BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                val compact = maxWidth < 520.dp
                val qrSize = if (compact) 160.dp else 192.dp
                val qr: @Composable () -> Unit = {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        // 二维码必须保持白底黑码才能被扫描，因此不跟随主题；加描边避免浅色主题下与卡片融合
                        modifier = Modifier
                            .size(qrSize)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    ) {
                        if (bridgeUrl.isEmpty()) {
                            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                                Text("等待桥接服务", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            }
                        } else if (bitmap != null) {
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "扫码连接手机网页", modifier = Modifier.padding(8.dp))
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                // centered=true（窄屏竖排）：文案与上方二维码一起居中，避免“码居中、字靠左”的错位
                val details: @Composable (centered: Boolean) -> Unit = { centered ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
                    ) {
                        val align = if (centered) TextAlign.Center else TextAlign.Start
                        Text("手机扫码连接", style = MaterialTheme.typography.titleLarge, textAlign = align)
                        Text(
                            if (bridgeUrl.isNotEmpty()) "在手机浏览器中打开录像与照片媒体库" else "桥接服务启动后会显示配对二维码",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = align
                        )
                        Text(
                            "二维码仅在本次服务运行期间有效",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = align
                        )
                    }
                }
                if (compact) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        qr()
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { details(true) }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        qr()
                        Box(Modifier.weight(1f)) { details(false) }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeActionCard(
        title: String,
        description: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        modifier: Modifier = Modifier,
        compact: Boolean = false,
        onClick: () -> Unit
    ) {
        val minHeight = if (compact) 76.dp else 112.dp
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder()
        ) {
            // 用 heightIn 与卡片最小高度对齐，使内容在卡片内垂直居中；
            // 不使用 fillMaxSize，避免父层高度无界时把 min 约束撑成无限大
            Row(
                Modifier.fillMaxWidth().heightIn(min = minHeight).padding(if (compact) 14.dp else 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (compact) 24.dp else 34.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                    Text(
                        description,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 箭头用次级色 + 标题字号：与右侧标题区分层级，不再抢主标题视觉
                Text("›", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun generateQr(text: String): Bitmap {
        val size = 320
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        return bmp
    }
}
