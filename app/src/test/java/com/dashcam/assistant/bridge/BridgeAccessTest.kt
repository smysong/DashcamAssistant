package com.dashcam.assistant.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAccessTest {
    @Test
    fun pairingTokenIsRequiredToCreateSession() {
        val access = BridgeAccess("one-time-pairing-secret")
        assertTrue(access.hasPairingToken("one-time-pairing-secret"))
        assertFalse(access.hasPairingToken("wrong-secret"))
        assertFalse(access.hasPairingToken(null))
    }

    @Test
    fun onlyMatchingSessionCookieAuthorizesRequests() {
        val access = BridgeAccess("session-secret")
        assertTrue(access.hasSessionCookie("theme=dark; dashcam_session=session-secret"))
        assertFalse(access.hasSessionCookie("dashcam_session=wrong-secret"))
        assertFalse(access.hasSessionCookie("other=session-secret"))
        assertFalse(access.hasSessionCookie(null))
    }

    @Test
    fun pairingUrlAddsEncodedTokenWithoutReplacingExistingQuery() {
        val access = BridgeAccess("a token")
        assertTrue(access.pairingUrl("http://192.168.43.1:8080/").endsWith("?token=a+token"))
        assertTrue(access.pairingUrl("http://192.168.43.1:8080/?from=qr").endsWith("&token=a+token"))
    }
}
