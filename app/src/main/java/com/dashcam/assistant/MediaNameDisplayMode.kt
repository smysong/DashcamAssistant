package com.dashcam.assistant

import android.content.Context

enum class MediaNameDisplayMode(val label: String, val preferenceValue: String) {
    FRIENDLY_ONLY("友好名称", "friendly"),
    ORIGINAL_ONLY("原始文件名", "original"),
    FRIENDLY_AND_ORIGINAL("友好名称 + 原始文件名", "both");

    companion object {
        fun fromPreference(value: String?): MediaNameDisplayMode =
            entries.firstOrNull { it.preferenceValue == value } ?: FRIENDLY_ONLY
    }
}

fun formatMediaName(
    friendlyName: String?,
    originalName: String?,
    mode: MediaNameDisplayMode
): String {
    val original = originalName.orEmpty().trim()
    val friendly = friendlyName.orEmpty().trim().ifEmpty { original }
    return when (mode) {
        MediaNameDisplayMode.FRIENDLY_ONLY -> friendly
        MediaNameDisplayMode.ORIGINAL_ONLY -> original.ifEmpty { friendly }
        MediaNameDisplayMode.FRIENDLY_AND_ORIGINAL ->
            if (friendly.isNotEmpty() && !friendly.equals(original, ignoreCase = true)) {
                "$friendly · $original"
            } else {
                friendly.ifEmpty { original }
            }
    }
}

object MediaNameDisplayPreference {
    private const val PREFS = "media_library"
    private const val KEY_MODE = "name_display_mode"

    fun load(context: Context): MediaNameDisplayMode =
        MediaNameDisplayMode.fromPreference(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        )

    fun save(context: Context, mode: MediaNameDisplayMode) {
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.preferenceValue)
                .commit()
        ) { "无法保存文件名显示设置" }
    }
}
