package com.dashcam.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用中心「检查更新 + 下载 + 校验 + 安装」接入（遵循 APK 接入应用中心版本检查与升级指南）。
 *
 * 服务端开放接口（无需鉴权）：
 *   GET /api/public/check-update?package=<包名>&versionCode=<当前>
 *   GET /api/public/download/<包名>?version_code=<版本号>
 *
 * 设计取舍：为保证构建机无需拉取新依赖，使用 HttpURLConnection + org.json + coroutines，
 * 不引入 OkHttp/Gson。APK 下载支持 HTTP Range 断点续传，完成后校验 SHA-256 再安装。
 */
object UpdateCenter {
    /** 应用中心测试服务器地址（HTTP 明文，已开 usesCleartextTraffic）。 */
    const val SERVER_BASE = "http://172.16.1.189:8081"

    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    sealed interface CheckResult {
        data object NoUpdate : CheckResult
        data class Available(val latest: LatestVersion) : CheckResult
        data object Error : CheckResult
    }

    data class LatestVersion(
        val versionCode: Int,
        val versionName: String,
        val apkSize: Long,
        val sha256: String,
        val changelog: String,
        val forceUpdate: Boolean,
        val downloadUrl: String
    )

    fun currentVersionCode(context: Context): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (_: Exception) { 0 }

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    /** 调用检查更新接口（需在协程中调用）。 */
    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val vc = currentVersionCode(context)
        val url = "$SERVER_BASE/api/public/check-update?package=${Uri.encode(pkg)}&versionCode=$vc"
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return@withContext CheckResult.Error }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            parseCheck(body)
        } catch (_: Exception) {
            CheckResult.Error
        }
    }

    private fun parseCheck(json: String): CheckResult {
        return try {
            val root = JSONObject(json)
            if (root.optInt("code", -1) != 0) return CheckResult.Error
            val data = root.optJSONObject("data") ?: return CheckResult.NoUpdate
            if (!data.optBoolean("has_update", false)) return CheckResult.NoUpdate
            val latest = data.optJSONObject("latest") ?: return CheckResult.NoUpdate
            CheckResult.Available(
                LatestVersion(
                    versionCode = latest.optInt("version_code", 0),
                    versionName = latest.optString("version_name", ""),
                    apkSize = latest.optLong("apk_size", 0L),
                    sha256 = latest.optString("sha256", ""),
                    changelog = latest.optString("changelog", ""),
                    forceUpdate = latest.optBoolean("force_update", false),
                    downloadUrl = latest.optString("download_url", "")
                )
            )
        } catch (_: Exception) {
            CheckResult.Error
        }
    }

    /** 下载（支持断点续传）+ 校验 SHA-256 + 安装。onProgress 为 0..100 的字节比例。 */
    suspend fun downloadAndInstall(
        context: Context,
        latest: LatestVersion,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val file = File(dir, "update_${latest.versionCode}.apk")
        if (!downloadWithResume(latest.downloadUrl, file, latest.apkSize, onProgress)) return@withContext false
        // 校验 SHA-256（不一致删掉重试一次）
        if (!file.sha256().equals(latest.sha256, ignoreCase = true)) {
            file.delete()
            if (!downloadWithResume(latest.downloadUrl, file, latest.apkSize, onProgress)) return@withContext false
            if (!file.sha256().equals(latest.sha256, ignoreCase = true)) {
                file.delete()
                return@withContext false
            }
        }
        withContext(Dispatchers.Main) { install(context, file) }
        true
    }

    private fun downloadWithResume(
        url: String, file: File, expectedSize: Long, onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            var start = 0L
            if (file.exists()) {
                val cur = file.length()
                if (expectedSize > 0 && cur in 1 until expectedSize) start = cur
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                if (start > 0) setRequestProperty("Range", "bytes=$start-")
            }
            val code = conn.responseCode
            val append = start > 0 && code == 206
            if (code != 200 && code != 206) { conn.disconnect(); return false }
            val total = if (code == 206) {
                conn.getHeaderField("Content-Range")?.let { parseContentRangeTotal(it, start) } ?: expectedSize
            } else expectedSize
            conn.inputStream.use { input ->
                FileOutputStream(file, append).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var written = start
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                    }
                    out.flush()
                }
            }
            conn.disconnect()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseContentRangeTotal(header: String, start: Long): Long {
        val slash = header.lastIndexOf('/')
        if (slash >= 0) {
            val total = header.substring(slash + 1).toLongOrNull()
            if (total != null && total > 0) return total
        }
        return start
    }

    private fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX, file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun File.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(8192)
            var n = input.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
