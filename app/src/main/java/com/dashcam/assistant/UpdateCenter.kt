package com.dashcam.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
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
    /** 应用中心服务器地址（HTTPS）。 */
    const val SERVER_BASE = "https://app.songmy.top"

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

    /**
     * 下载（支持 HTTP Range 断点续传）+ 校验 SHA-256 + 安装。onProgress 为 0..100 的字节比例。
     *
     * 优化：
     * - 若磁盘缓存中已存在「完整且 SHA-256 校验通过」的安装包，则直接安装，避免重复下载；
     * - 否则基于已存在的部分文件发起 Range 续传（[downloadWithResume]）。
     */
    suspend fun downloadAndInstall(
        context: Context,
        latest: LatestVersion,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val file = File(dir, "update_${latest.versionCode}.apk")

        // 快速路径：磁盘已存在「完整且校验通过」的安装包 → 直接安装，避免重复下载
        if (cachedVerified(file, latest)) {
            onProgress(100)
            withContext(Dispatchers.Main) { install(context, file) }
            return@withContext true
        }

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

    /** 判断磁盘缓存的安装包是否已完整且 SHA-256 校验通过（可跳过下载）。 */
    private fun cachedVerified(file: File, latest: LatestVersion): Boolean {
        if (!file.exists() || latest.sha256.isBlank() || latest.apkSize <= 0) return false
        if (file.length() != latest.apkSize) return false
        return file.sha256().equals(latest.sha256, ignoreCase = true)
    }

    /**
     * 应用启动时调用：清理升级缓存目录中「版本号 ≤ 当前运行版本」的安装包。
     * 升级成功后 APP 以新版本号重启，旧安装包与刚装完的当前版本包都会被删掉；
     * 尚未安装的高版本安装包（versionCode > 当前）予以保留，以供缓存复用、避免重复下载。
     */
    fun cleanupStalePackages(context: Context) {
        val cur = currentVersionCode(context)
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) return
        dir.listFiles { f -> f.isFile && f.name.startsWith("update_") && f.name.endsWith(".apk") }
            ?.forEach { f ->
                val vc = f.name.removePrefix("update_").removeSuffix(".apk").toIntOrNull()
                if (vc != null && vc <= cur) f.delete()
            }
    }

    /** 清空升级/安装包缓存目录（设置页「清除缓存」调用）。返回释放的字节数。 */
    fun clearUpdateCache(context: Context): Long {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { f -> freed += f.length(); f.delete() }
        return freed
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
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 优先走系统安装器（ACTION_VIEW 可被某活动解析）
        val hasSystemInstaller = viewIntent.resolveActivity(context.packageManager) != null
        if (hasSystemInstaller) {
            context.startActivity(viewIntent)
        } else {
            // 无系统默认安装器时，使用 PackageInstaller 会话 API 直接安装升级
            val ok = installViaPackageInstaller(context, file)
            if (!ok) {
                Toast.makeText(context, "未找到系统安装器，且内置安装失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 无系统默认安装器时的兜底：通过 [PackageInstaller] 会话直接提交安装。
     * 该路径不依赖外部「包安装器」活动来解析 ACTION_VIEW，由系统 PackageManager 直接处理升级。
     */
    private fun installViaPackageInstaller(context: Context, file: File): Boolean = try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("dashcam_update", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val action = "com.dashcam.assistant.action.UPDATE_INSTALL_RESULT"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val self = this
                    runCatching { context.unregisterReceiver(self) }
                    val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    val msg = if (status == PackageInstaller.STATUS_SUCCESS) {
                        "应用已升级，请重新打开"
                    } else {
                        "安装失败：${intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "未知错误"}"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pi.intentSender)
        }
        true
    } catch (e: Exception) {
        AppLog.w("UpdateCenter", "PackageInstaller 安装失败：${e.message}")
        false
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
