package com.example.identity.tools.auth_qr.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stores the six-digit confirmation code as HMAC-SHA256 under a server-side pepper: a plain hash of
 * six digits is reversed in 10^6 steps by anyone who can read `login_request`. Own copy of the rule,
 * since modules stay decoupled.
 */
@Component
class ConfirmationCodeDigest(@Value("\${identity.secrets.otp-pepper:}") configuredPepper: String) {

    /** Blank means a random pepper per boot - a code in flight lives two minutes, a restart costs one. */
    private val pepper: ByteArray = configuredPepper.takeIf { it.isNotBlank() }?.toByteArray()
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun of(code: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pepper, HMAC_ALGORITHM))
        return Base64.getEncoder().encodeToString(mac.doFinal(code.trim().toByteArray()))
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
