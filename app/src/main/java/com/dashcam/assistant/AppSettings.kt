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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
