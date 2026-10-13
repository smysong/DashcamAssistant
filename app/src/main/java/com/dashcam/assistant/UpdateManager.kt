package com.dashcam.assistant

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用更新相关共享状态（连接 [UpdateCenter] 与 UI）。
 *
 * 两层状态：
 * - [updateAvailable]：已发现但尚未安装的更新（持久提示圆点用）。只要检测到更新就置位，
 *   **即使用户关闭了升级弹窗也保持显示**，直到真正安装完成或重新检查无更新才清除。
 * - [pendingUpdate]：当前是否正在展示升级弹窗（为 null 则不弹）。关闭弹窗只清它，不清 [updateAvailable]。
 *
 * 自动检查：开关 [isAutoCheckEnabled] + 单次启动仅一次 [autoCheckedOnce]；点击「以后再说」后因
 * [suppressedThisSession] 本次会话不再自动检查与弹窗（但已存在的圆点仍保留）。
 */
object UpdateManager {
    private const val PREFS = "update_center"
    private const val KEY_AUTO = "auto_check"

    /** 已发现但尚未安装的更新（持久提示圆点）。 */
    val updateAvailableState = mutableStateOf<UpdateCenter.LatestVersion?>(null)
    var updateAvailable by updateAvailableState
        private set

    /** 当前待展示的升级弹窗版本；为 null 时不弹窗。 */
    val pendingUpdateState = mutableStateOf<UpdateCenter.LatestVersion?>(null)
    var pendingUpdate by pendingUpdateState
        private set

    /** 当前弹窗是否由「后台自动检查」触发。 */
    var pendingFromAuto by mutableStateOf(false)
        private set

    /** 本次会话是否已因「以后再说」而抑制自动检查。 */
    var suppressedThisSession by mutableStateOf(false)
        private set

    /** 单次启动是否已执行过自动检查。 */
    private var autoCheckedOnce = false

    /**
     * 后台下载作用域：随进程存活，不随设置弹窗/升级弹窗关闭而取消，
     * 从而实现「关闭设置窗口后仍在后台继续下载」。
     */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否正在后台下载（共享状态，设置页与升级弹窗均可观察）。 */
    val downloadingState = mutableStateOf(false)
    var isDownloading by downloadingState
        private set

    /** 后台下载进度 0..100（共享状态）。 */
    val downloadProgressState = mutableStateOf(0)
    var downloadProgress by downloadProgressState
        private set

    /** 当前正在下载的目标版本（用于 UI 判断是否匹配）。 */
    val downloadingVersionState = mutableStateOf<UpdateCenter.LatestVersion?>(null)
    var downloadingVersion by downloadingVersionState
        private set

    /**
     * 在后台启动「下载 + SHA-256 校验 + 安装」，不阻塞调用方 UI。
     * 幂等：下载进行中再次调用直接忽略，避免并发重复下载。
     * 下载/安装由 [UpdateCenter] 负责；失败以 Toast 提示。
     */
    fun startDownload(context: Context, latest: UpdateCenter.LatestVersion) {
        if (isDownloading) return
        downloadScope.launch {
            try {
                isDownloading = true
                downloadingVersion = latest
                downloadProgress = 0
                val ok = UpdateCenter.downloadAndInstall(context.applicationContext, latest) { p ->
                    downloadProgress = p
                }
                if (!ok) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context.applicationContext, "更新包下载或校验失败", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                isDownloading = false
            }
        }
    }

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, false)

    fun setAutoCheckEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, value).apply()
    }

    /** 发现更新时记录（手动/自动共用）：同时置位圆点与弹窗。 */
    private fun found(latest: UpdateCenter.LatestVersion, fromAuto: Boolean) {
        updateAvailable = latest
        pendingFromAuto = fromAuto
        pendingUpdate = latest
    }

    /** 手动「检查更新」发现新版本。 */
    fun showManual(latest: UpdateCenter.LatestVersion) = found(latest, fromAuto = false)

    /** 后台自动检查发现新版本（受调用方约束，不在此处判断开关/抑制）。 */
    fun showAuto(latest: UpdateCenter.LatestVersion) = found(latest, fromAuto = true)

    /**
     * 关闭升级弹窗（「以后再说」/返回键）：仅隐藏弹窗，保留圆点提示。
     * 自动触发时同时抑制本次会话后续的自动检查与弹窗。
     */
    fun onLater() {
        if (pendingFromAuto) suppressedThisSession = true
        pendingUpdate = null
    }

    /** 弹窗被直接关闭（返回键等），与「以后再说」一致：仅隐藏弹窗，保留圆点。 */
    fun onDismiss() {
        pendingUpdate = null
    }

    /** 重新检查到无更新时清除提示圆点（与弹窗）。 */
    fun clearAvailable() {
        updateAvailable = null
        pendingUpdate = null
    }

    /** 启动后后台自动检查：受开关与「本次会话抑制」约束，且单次启动仅执行一次。 */
    suspend fun maybeAutoCheck(context: Context) {
        if (autoCheckedOnce) return
        autoCheckedOnce = true
        if (!isAutoCheckEnabled(context) || suppressedThisSession) return
        when (val r = UpdateCenter.check(context)) {
            is UpdateCenter.CheckResult.Available -> showAuto(r.latest)
            else -> Unit
        }
    }
}
