package com.dashcam.assistant.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlaybackSupportTest {
    @Test
    fun guessesBrowserVideoMimeWhenDvrReturnsBinaryType() {
        assertEquals("video/mp4", guessMediaContentType("/SD0/NOR/M_video/clip.MP4", "application/octet-stream"))
        assertEquals("video/x-matroska", guessMediaContentType("clip.mkv", null))
    }

    @Test
    fun guessesBrowserImageMimeWhenDvrReturnsBinaryType() {
        assertEquals("image/jpeg", guessMediaContentType("/SD0/PHO/M_photo/clip.JPG", "application/octet-stream"))
        assertEquals("image/png", guessMediaContentType("clip.png", null))
    }

    @Test
    fun mobileThumbnailUrlEncodesThePathAsAProtectedApiQuery() {
        assertEquals(
            "/api/thumb?path=%2FSD0%2FPHO%2FM_photo%2Ffront+camera%23one.JPG",
            mobileThumbnailUrl("/SD0/PHO/M_photo/front camera#one.JPG")
        )
    }

    @Test
    fun preservesExplicitMediaTypeAndFallsBackForUnknownFiles() {
        assertEquals("video/custom", guessMediaContentType("clip.bin", "video/custom; charset=binary"))
        assertEquals("application/octet-stream", guessMediaContentType("clip.unknown", null))
    }

}
