package com.dashcam.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCachePolicyTest {
    @Test
    fun playbackCacheIsBoundedAndDisabledBelowTheFreeSpaceReserve() {
        assertEquals(256L * 1024 * 1024, MediaCachePolicy.PLAYBACK_CACHE_MAX_BYTES)
        assertFalse(MediaCachePolicy.shouldUsePlaybackDiskCache(MediaCachePolicy.MIN_FREE_BYTES_FOR_CACHING - 1))
        assertTrue(MediaCachePolicy.shouldUsePlaybackDiskCache(MediaCachePolicy.MIN_FREE_BYTES_FOR_CACHING))
    }

    @Test
    fun thumbnailAndImageWorkingSetLimitsAreBounded() {
        assertEquals(64L * 1024 * 1024, MediaCachePolicy.THUMBNAIL_CACHE_MAX_BYTES)
        assertEquals(24L * 1024 * 1024, MediaCachePolicy.MAX_THUMBNAIL_SOURCE_BYTES)
        assertEquals(64L * 1024 * 1024, MediaCachePolicy.MAX_FULL_PHOTO_SOURCE_BYTES)
        assertEquals(1600, MediaCachePolicy.FULL_PHOTO_MAX_DIMENSION)
    }
}
