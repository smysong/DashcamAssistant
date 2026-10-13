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
import kotlinx.coroutines.launch

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
    onDismiss: () -> Unit
) {
    var pageSize by remember { mutableStateOf(initialPageSize) }
    var nameMode by remember { mutableStateOf(initialNameMode) }

    // 检查更新相关状态
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }

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
                        val thumbCache = remember { MediaThumbnailCache.get(context) }
                        FilledTonalButton(
                            onClick = {
                                val freed = thumbCache.clear()
                                Toast.makeText(context, "已清除缩略图缓存 ${formatCacheBytes(freed)}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("清除缩略图缓存") }
                        FilledTonalButton(
                            onClick = {
                                val freed = MediaPlaybackDataSource.clearCache(context)
                                Toast.makeText(context, "已清除回放缓存 ${formatCacheBytes(freed)}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("清除回放缓存") }
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
                            // 发现新版本：点击「立即更新」直接触发 下载 + SHA-256 校验 + 安装，
                            // 不再二次弹出「发现新版本」确认弹窗。
                            FilledTonalButton(
                                onClick = {
                                    downloading = true
                                    downloadProgress = 0
                                    scope.launch {
                                        val ok = UpdateCenter.downloadAndInstall(context, avail) { p ->
                                            downloadProgress = p
                                        }
                                        downloading = false
                                        if (!ok) {
                                            Toast.makeText(context, "更新包下载或校验失败", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !downloading,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (downloading) "下载中… $downloadProgress%" else "立即更新") }
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
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = { if (!latest.forceUpdate && !downloading) onDismiss() },
        confirmButton = {
            if (downloading) {
                TextButton(onClick = { }, enabled = false) { Text("下载中…") }
            } else {
                TextButton(onClick = {
                    downloading = true
                    progress = 0
                    scope.launch {
                        val ok = UpdateCenter.downloadAndInstall(context, latest) { p -> progress = p }
                        if (!ok) {
                            downloading = false
                            Toast.makeText(context, "更新包下载或校验失败", Toast.LENGTH_LONG).show()
                        } else {
                            // 安装界面会接管，关闭本弹窗
                            downloading = false
                            onDismiss()
                        }
                    }
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
                    Text(change, style = MaterialTheme.typography.bodyMedium)
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
