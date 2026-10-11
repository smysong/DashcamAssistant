package com.dashcam.assistant

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingDeque

/**
 * 应用内日志环形缓冲（开发者模式用）。
 *
 * 设计：
 * - 既转发到系统 Logcat（真实车机/手机可读 `adb logcat`），又保留最近 [MAX] 条到内存，
 *   供手机网页"开发者模式"查看与导出 APP 日志。
 * - 安装全局未捕获异常处理器，崩溃也会被记入缓冲，便于现场排查。
 *
 * 用法：在需要埋点的地方调用 `AppLog.d("Tag", "msg")` / `AppLog.e("Tag", "msg", throwable)`。
 */
object AppLog {
    private const val MAX = 2000
    private val buf = LinkedBlockingDeque<String>(MAX)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINESE)

    init {
        installCrashHandler()
    }

    fun v(tag: String, msg: String) = push("V", tag, msg)
    fun d(tag: String, msg: String) = push("D", tag, msg)
    fun i(tag: String, msg: String) = push("I", tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) {
        val m = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        push("W", tag, m)
    }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val m = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        push("E", tag, m)
    }

    private fun push(level: String, tag: String, msg: String) {
        val ts = fmt.format(Date())
        val line = "$ts $level/$tag: $msg"
        when (level) {
            "V" -> Log.v(tag, msg)
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
        }
        buf.offerLast(line)
        // 容量上限保护：满了则丢弃最旧一条（LinkedBlockingDeque(MAX) 的 offer 不会自动淘汰）
        if (buf.size > MAX) buf.pollFirst()
    }

    /** 旧→新 返回全部缓冲行（用于展示/导出）。 */
    fun snapshot(): List<String> = ArrayList(buf)

    /** 导出为纯文本（用于下载）。 */
    fun exportText(): String = if (buf.isEmpty()) "（暂无日志）" else buf.joinToString("\n")

    fun clear() = buf.clear()

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(prev))
    }

    private class CrashHandler(private val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            e("CRASH", "未捕获异常 @${t.name}: ${e.message}", e)
            prev?.uncaughtException(t, e)
        }
    }
}
