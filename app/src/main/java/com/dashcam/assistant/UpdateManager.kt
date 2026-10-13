package com.dashcam.assistant

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 应用更新相关共享状态（连接 [UpdateCenter] 与 UI）。
 *
 * - 自动检查开关（持久化到 SharedPreferences）。
 * - 升级提示的「待展示版本」[pendingUpdate]：手动「检查更新」与启动后「后台自动检查」共用同一弹窗。
 * - 会话抑制：后台自动发现新版本并弹出提示后，用户点击「以后再说」则**本次 APP 运行期间**不再检查与提示
 *   （[suppressedThisSession]，进程级，进程重启后失效，故不持久化）。
 * - 单次启动只自动检查一次（[autoCheckedOnce]），避免深浅色切换重建 Activity 后重复弹窗。
 */
object UpdateManager {
    private const val PREFS = "update_center"
    private const val KEY_AUTO = "auto_check"

    /** 当前待展示的升级版本状态（供 Composable 用 by 委托观察）；为 null 时不弹窗。 */
    val pendingUpdateState = mutableStateOf<UpdateCenter.LatestVersion?>(null)
    var pendingUpdate by pendingUpdateState
        private set

    /** 当前弹窗是否由「后台自动检查」触发（决定「以后再说」是否抑制本次会话）。 */
    var pendingFromAuto by mutableStateOf(false)
        private set

    /** 本次会话是否已因「以后再说」而抑制自动检查。 */
    var suppressedThisSession by mutableStateOf(false)
        private set

    /** 单次启动是否已执行过自动检查。 */
    private var autoCheckedOnce = false

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, false)

    fun setAutoCheckEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, value).apply()
    }

    /** 手动「检查更新」发现新版本：不触发会话抑制。 */
    fun showManual(latest: UpdateCenter.LatestVersion) {
        pendingFromAuto = false
        pendingUpdate = latest
    }

    /** 后台自动检查发现新版本：标记来自自动检查（点击「以后再说」将抑制本会话）。 */
    fun showAuto(latest: UpdateCenter.LatestVersion) {
        pendingFromAuto = true
        pendingUpdate = latest
    }

    /** 「以后再说」：关闭弹窗；若是自动检查触发的，则抑制本次会话剩余时间的自动检查与提示。 */
    fun onLater() {
        if (pendingFromAuto) suppressedThisSession = true
        pendingUpdate = null
    }

    /** 弹窗被直接关闭（返回键等），不抑制。 */
    fun onDismiss() {
        pendingUpdate = null
    }

    /** 启动后后台自动检查：受开关与会话抑制约束，且单次启动仅执行一次。 */
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
