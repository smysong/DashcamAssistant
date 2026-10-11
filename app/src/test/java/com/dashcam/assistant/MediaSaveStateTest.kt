package com.dashcam.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSaveStateTest {
    @Test
    fun saveProgressAndSuccessTransitionAreVisibleAndResettable() {
        val copying = MediaSaveState()
            .begin()
            .progress(512, 1024)
        assertEquals(MediaSavePhase.COPYING, copying.phase)
        assertEquals(512L, copying.copiedBytes)
        assertEquals(1024L, copying.totalBytes)

        val saved = copying.succeed()
        assertEquals(MediaSavePhase.SAVED, saved.phase)
        assertEquals("", saved.error)

        val retry = saved.begin()
        assertEquals(MediaSavePhase.COPYING, retry.phase)
        assertEquals(0L, retry.copiedBytes)
        assertEquals(null, retry.totalBytes)
    }

    @Test
    fun cancellationAndFailureRemainDistinctTerminalStates() {
        val cancelled = MediaSaveState().begin().progress(128, null).cancel()
        assertEquals(MediaSavePhase.CANCELLED, cancelled.phase)
        assertEquals(128L, cancelled.copiedBytes)

        val failed = MediaSaveState().begin().fail("DVR 返回 HTTP 500")
        assertEquals(MediaSavePhase.FAILED, failed.phase)
        assertEquals("DVR 返回 HTTP 500", failed.error)
        assertEquals(MediaSavePhase.FAILED, MediaSaveState().fail("").phase)
    }

    @Test
    fun terminalStateCannotBeOverwrittenByLateProgress() {
        val saved = MediaSaveState().begin().succeed()

        assertEquals(saved, saved.progress(999, 1000))
        assertEquals(saved, saved.fail("late error"))
    }
}
