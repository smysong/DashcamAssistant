package com.dashcam.assistant

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开发者模式面板：查看 / 刷新 / 下载 / 清除运行日志，并可退出开发者模式。
 * 日志来自 [AppLog] 的内存环形缓冲（安装时即开始采集，含崩溃栈）。
 */
@Composable
fun DevPanel(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var logs by remember { mutableStateOf(AppLog.snapshot()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        title = { Text("开发者模式 · 运行日志", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { logs = AppLog.snapshot() },
                        modifier = Modifier.weight(1f)
                    ) { Text("刷新") }
                    FilledTonalButton(
                        onClick = {
                            val path = exportLogs(context)
                            Toast.makeText(
                                context,
                                if (path != null) "已导出至 Download 目录" else "导出失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("下载日志") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            AppLog.clear()
                            logs = emptyList()
                            Toast.makeText(context, "日志已清除", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("清除日志") }
                }
                Spacer(Modifier.height(8.dp))
                // 最新在底部，便于看最新输出
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    reverseLayout = true
                ) {
                    items(logs) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = if (DevMode.isUnlocked()) {
            {
                TextButton(onClick = { DevMode.exit(context); onDismiss() }) { Text("退出开发者模式") }
            }
        } else null
    )
}

/**
 * 将内存日志导出为 txt 并写入外部 Download 目录（已被 FileProvider 以 `downloads` 暴露），
 * 通过系统分享器分发（用户可另存/发送）。返回文件绝对路径，失败返回 null。
 */
fun exportLogs(context: Context): String? = runCatching {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
    dir.mkdirs()
    val name = "app_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINESE).format(Date())}.txt"
    val file = File(dir, name)
    file.writeText(AppLog.exportText())
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "导出运行日志"))
    file.absolutePath
}.getOrNull()
