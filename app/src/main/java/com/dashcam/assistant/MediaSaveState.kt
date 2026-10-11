package com.dashcam.assistant

internal enum class MediaSavePhase {
    IDLE,
    COPYING,
    SAVED,
    CANCELLED,
    FAILED
}

internal data class MediaSaveState(
    val phase: MediaSavePhase = MediaSavePhase.IDLE,
    val copiedBytes: Long = 0,
    val totalBytes: Long? = null,
    val error: String = ""
) {
    fun begin(): MediaSaveState = copy(
        phase = MediaSavePhase.COPYING,
        copiedBytes = 0,
        totalBytes = null,
        error = ""
    )

    fun progress(copied: Long, total: Long?): MediaSaveState =
        if (phase == MediaSavePhase.COPYING) {
            copy(copiedBytes = copied.coerceAtLeast(0), totalBytes = total?.coerceAtLeast(0))
        } else {
            this
        }

    fun succeed(): MediaSaveState =
        if (phase == MediaSavePhase.COPYING) copy(phase = MediaSavePhase.SAVED, error = "") else this

    fun cancel(): MediaSaveState =
        if (phase == MediaSavePhase.COPYING) copy(phase = MediaSavePhase.CANCELLED, error = "") else this

    fun fail(message: String): MediaSaveState =
        if (phase == MediaSavePhase.COPYING || phase == MediaSavePhase.IDLE) {
            copy(phase = MediaSavePhase.FAILED, error = message.ifBlank { "保存失败" })
        } else {
            this
        }
}
