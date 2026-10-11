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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
            }
        }
    }

    @Composable
    private fun MainScreen(onManage: () -> Unit, onLive: () -> Unit) {
        val status by BridgeState.status.collectAsState()
        // QR only exposes the paired bridge URL after the service has created its session token.
        val bridgeUrl = status.bridgeUrl
        var qr by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(bridgeUrl) { qr = if (bridgeUrl.isNotEmpty()) generateQr(bridgeUrl) else null }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 首页三档自适应：手机竖屏单栏、平板竖屏单栏放宽、平板横屏/车机双栏
                val metrics = dashcamLayoutMetrics(dashcamWindowClass())
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = metrics.homeHorizontalPadding,
                            vertical = metrics.homeVerticalPadding
                        ),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("行车记录仪助手", style = MaterialTheme.typography.headlineLarge)
                        Text("设备状态与媒体访问", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    ConnectionCard(status = status, onRescan = {
                        AppLog.i("车机", "触发重新扫描")
                        BridgeState.onReconnect?.invoke()
                    })
                    if (metrics.twoPaneHome) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            PairingCard(qr, bridgeUrl, Modifier.weight(1.1f))
                            Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                HomeActionCard(
                                    title = "媒体库",
                                    description = "浏览录像、紧急录像与照片",
                                    icon = Icons.Filled.Storage,
                                    onClick = { AppLog.i("车机", "打开媒体管理"); onManage() }
                                )
                                HomeActionCard(
                                    title = "实时预览",
                                    description = "查看当前 DVR 直播画面",
                                    icon = Icons.Filled.LiveTv,
                                    onClick = onLive
                                )
                            }
                        }
                    } else {
                        PairingCard(qr, bridgeUrl, Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeActionCard(
                                title = "媒体库",
                                description = "录像与照片",
                                icon = Icons.Filled.Storage,
                                modifier = Modifier.weight(1f),
                                onClick = { AppLog.i("车机", "打开媒体管理"); onManage() }
                            )
                            HomeActionCard(
                                title = "实时预览",
                                description = "查看直播画面",
                                icon = Icons.Filled.LiveTv,
                                modifier = Modifier.weight(1f),
                                onClick = onLive
                            )
                        }
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
                        Text(
                            "行车记录仪助手 | Version：${BuildConfig.VERSION_NAME}",
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ConnectionCard(status: DvrStatusUi, onRescan: () -> Unit) {
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
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = statusColor, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            status.connected -> "已连接 DVR"
                            status.discovered -> "已发现 DVR"
                            status.error.isNotEmpty() -> "连接遇到问题"
                            else -> "正在搜索附近设备"
                        },
                        style = MaterialTheme.typography.titleLarge
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
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = onRescan, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("重新扫描", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
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
            BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
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
        onClick: () -> Unit
    ) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = 112.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder()
        ) {
            // 用 heightIn 与卡片最小高度对齐，使内容在卡片内垂直居中；
            // 不使用 fillMaxSize，避免父层高度无界时把 min 约束撑成无限大
            Row(
                Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 箭头用次级色 + 标题字号：与右侧标题区分层级，不再抢主标题视觉
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
