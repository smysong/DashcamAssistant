package com.dashcam.assistant

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * 开发者模式开关。
 * 在首页连续点击标题「行车记录仪助手」5 次解锁；解锁状态持久化，解锁后可在设置页再次进入。
 */
object DevMode {
    private const val PREFS = "dev_mode"
    private const val KEY = "unlocked"
    private val unlockedState = mutableStateOf(false)

    fun isUnlocked(): Boolean = unlockedState.value

    fun load(context: Context) {
        unlockedState.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun unlock(context: Context) {
        unlockedState.value = true
        persist(context)
    }

    fun exit(context: Context) {
        unlockedState.value = false
        persist(context)
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, unlockedState.value).apply()
    }
}
