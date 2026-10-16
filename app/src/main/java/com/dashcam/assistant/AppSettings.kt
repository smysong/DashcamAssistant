package com.dashcam.assistant

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 媒体库每页数量偏好（与「文件名显示」一起从底部控制条收拢到设置页）。 */
object PageSizePreference {
    private const val PREFS = "media_library"
    private const val KEY = "page_size"
    fun load(context: Context): Int {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 10)
        return if (v in listOf(10, 15, 20)) v else 10
    }
    fun save(context: Context, size: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, size).apply()
    }
}

@Composable
fun SettingsDialog(
    context: Context,
    initialPageSize: Int,
    initialNameMode: MediaNameDisplayMode,
    onPageSizeChange: (Int) -> Unit,
    onNameModeChange: (MediaNameDisplayMode) -> Unit,
    onDismiss: () -> Unit,
    onOpenDev: () -> Unit = {}
) {
    var pageSize by remember { mutableStateOf(initialPageSize) }
    var nameMode by remember { mutableStateOf(initialNameMode) }

    // 检查更新相关状态
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
        // 弹窗背景色与 App 主区域保持一致（默认 surface 与 background 存在色差，用户反馈不一致）
        containerColor = MaterialTheme.colorScheme.background,
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                SettingsSection("媒体库每页数量") {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 15, 20).forEach { sz ->
                            FilterChip(
                                selected = pageSize == sz,
                                onClick = {
                                    pageSize = sz
                                    PageSizePreference.save(context, sz)
                                    onPageSizeChange(sz)
                                },
                                label = { Text("$sz 项") }
                            )
                        }
                    }
                }
                SettingsSection("文件名显示") {
                    Column {
                        MediaNameDisplayMode.entries.forEach { mode ->
                            androidx.compose.foundation.layout.Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = nameMode == mode,
                                        onClick = {
                                            nameMode = mode
                                            MediaNameDisplayPreference.save(context, mode)
                                            onNameModeChange(mode)
                                        }
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = nameMode == mode, onClick = null)
                                Text(mode.label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                SettingsSection("缓存管理") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        // 合并为单一「清除缓存」：同时清理缩略图、回放、升级/安装包三类磁盘缓存
                        FilledTonalButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val thumb = MediaThumbnailCache.get(context).clear()
                                    val playback = MediaPlaybackDataSource.clearCache(context)
                                    // 升级/安装包缓存：下载进行中不清理，避免破坏正在写入的文件
                                    val downloading = UpdateManager.isDownloading
                                    val update = if (downloading) 0L else UpdateCenter.clearUpdateCache(context)
                                    val total = thumb + playback + update
                                    val skipped = downloading
                                    withContext(Dispatchers.Main) {
                                        val note = if (skipped) "（升级下载进行中，已跳过安装包清理）" else ""
                                        Toast.makeText(context, "已清除缓存 ${formatCacheBytes(total)}$note", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("清除缓存") }
                        // 开发者模式入口（仅解锁后显示）
                        if (DevMode.isUnlocked()) {
                            FilledTonalButton(
                                onClick = onOpenDev,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("开发者模式") }
                        }
                    }
                }
                SettingsSection("应用更新") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        val curVersion = remember { UpdateCenter.currentVersionName(context) }
                        val curCode = remember { UpdateCenter.currentVersionCode(context) }
                        Text(
                            "当前版本：v$curVersion（构建号 $curCode）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // 已检测到新版本：展示最新版本，并将按钮切换为「立即更新」
                        // 注意：此处必须快照为非委托局部 val，否则在 if (!= null) 内无法智能转换
                        val avail = UpdateManager.updateAvailableState.value
                        if (avail != null) {
                            Text(
                                "最新版本：v${avail.versionName}（构建号 ${avail.versionCode}）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        // 自动检查更新开关（持久化）
                        var autoCheck by remember { mutableStateOf(UpdateManager.isAutoCheckEnabled(context)) }
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("启动时自动检查更新", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = autoCheck,
                                onCheckedChange = {
                                    autoCheck = it
                                    UpdateManager.setAutoCheckEnabled(context, it)
                                }
                            )
                        }
                        if (avail != null) {
                            // 发现新版本：点击「立即更新」直接触发后台下载 + SHA-256 校验 + 安装。
                            // 下载在 UpdateManager 的独立作用域进行，关闭设置窗口也不会中断。
                            val bgDownloading by UpdateManager.downloadingState
                            val bgProgress by UpdateManager.downloadProgressState
                            FilledTonalButton(
                                onClick = { UpdateManager.startDownload(context, avail) },
                                enabled = !bgDownloading,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (bgDownloading) "下载中… $bgProgress%" else "立即更新") }
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    checking = true
                                    scope.launch {
                                        when (val r = UpdateCenter.check(context)) {
                                            UpdateCenter.CheckResult.NoUpdate -> {
                                                UpdateManager.clearAvailable()
                                                Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                                            }
                                            UpdateCenter.CheckResult.Error ->
                                                Toast.makeText(context, "检查更新失败，请稍后重试", Toast.LENGTH_SHORT).show()
                                            is UpdateCenter.CheckResult.Available ->
                                                UpdateManager.showManual(r.latest)
                                        }
                                        checking = false
                                    }
                                },
                                enabled = !checking,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (checking) "检查中…" else "检查更新") }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun UpdateDialog(
    latest: UpdateCenter.LatestVersion,
    onLater: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 下载状态统一来自 UpdateManager 的后台作用域，关闭弹窗也不会中断下载
    val downloading by UpdateManager.downloadingState
    val progress by UpdateManager.downloadProgressState

    AlertDialog(
        onDismissRequest = { if (!latest.forceUpdate) onDismiss() },
        confirmButton = {
            if (downloading) {
                TextButton(onClick = { }, enabled = false) { Text("下载中… $progress%") }
            } else {
                TextButton(onClick = {
                    // 启动后台下载并立即关闭弹窗（下载在 UpdateManager 作用域继续）
                    UpdateManager.startDownload(context, latest)
                    onDismiss()
                }) { Text("立即更新") }
            }
        },
        dismissButton = if (latest.forceUpdate || downloading) null else {
            { TextButton(onClick = onLater) { Text("以后再说") } }
        },
        title = { Text("发现新版本 v${latest.versionName}") },
        containerColor = MaterialTheme.colorScheme.background,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (latest.forceUpdate) {
                    Text(
                        "此版本为强制更新，请尽快升级以继续使用。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                val change = latest.changelog.trim()
                if (change.isNotEmpty()) {
                    Text("更新内容：", style = MaterialTheme.typography.titleSmall)
                    MarkdownText(change, style = MaterialTheme.typography.bodyMedium)
                }
                if (downloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("下载中… $progress%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/** 缓存字节数格式化（与 MediaBrowser.formatBytes 同款，避免跨文件重名冲突）。 */
internal fun formatCacheBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(value / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
        else -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    }
}
