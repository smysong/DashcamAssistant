package com.dashcam.assistant.bridge

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class BridgeAccess(private val token: String = createToken()) {
    private val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)

    fun pairingUrl(baseUrl: String): String {
        val separator = if (baseUrl.contains('?')) '&' else '?'
        return "$baseUrl${separator}token=${URLEncoder.encode(token, "UTF-8")}"
    }

    fun hasPairingToken(candidate: String?): Boolean = isTokenMatch(candidate)

    fun hasSessionCookie(cookieHeader: String?): Boolean =
        cookieHeader.orEmpty().split(';').any { part ->
            val separator = part.indexOf('=')
            separator > 0 &&
                part.substring(0, separator).trim() == COOKIE_NAME &&
                isTokenMatch(part.substring(separator + 1).trim())
        }

    fun sessionCookieHeader(): String =
        "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict"

    private fun isTokenMatch(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(tokenBytes, candidate.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        const val COOKIE_NAME = "dashcam_session"

        fun createToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
