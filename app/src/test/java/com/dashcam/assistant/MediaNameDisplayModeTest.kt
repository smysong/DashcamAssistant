package com.dashcam.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaNameDisplayModeTest {
    @Test
    fun friendlyOnlyUsesParsedNameAndFallsBackToOriginal() {
        assertEquals("2026-01-02", formatMediaName("2026-01-02", "20260102.mp4", MediaNameDisplayMode.FRIENDLY_ONLY))
        assertEquals("clip.mp4", formatMediaName("clip.mp4", "clip.mp4", MediaNameDisplayMode.FRIENDLY_ONLY))
        assertEquals("clip.mp4", formatMediaName(null, "clip.mp4", MediaNameDisplayMode.FRIENDLY_ONLY))
    }

    @Test
    fun originalOnlyShowsOriginalName() {
        assertEquals("20260102.mp4", formatMediaName("2026-01-02", "20260102.mp4", MediaNameDisplayMode.ORIGINAL_ONLY))
    }

    @Test
    fun combinedModeAvoidsDuplicateNames() {
        assertEquals(
            "2026-01-02 · 20260102.mp4",
            formatMediaName("2026-01-02", "20260102.mp4", MediaNameDisplayMode.FRIENDLY_AND_ORIGINAL)
        )
        assertEquals(
            "Clip.mp4",
            formatMediaName("Clip.mp4", "clip.mp4", MediaNameDisplayMode.FRIENDLY_AND_ORIGINAL)
        )
    }

    @Test
    fun unknownPreferenceFallsBackToFriendlyMode() {
        assertEquals(MediaNameDisplayMode.FRIENDLY_ONLY, MediaNameDisplayMode.fromPreference("invalid"))
        assertEquals(MediaNameDisplayMode.FRIENDLY_ONLY, MediaNameDisplayMode.fromPreference(null))
    }
}
