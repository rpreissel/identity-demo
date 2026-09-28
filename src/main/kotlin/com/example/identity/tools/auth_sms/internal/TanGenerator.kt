package com.example.identity.tools.auth_sms.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Generates and verifies tool-session-scoped TANs; the plaintext is never persisted. Stored as
 * HMAC-SHA256 under a server-side pepper: a six-digit TAN has only 10^6 preimages, so a plain hash
 * is reversed at once by anyone who can read `issued_tan_hash`. The pepper is what that attacker
 * does not have.
 */
@Component
class TanGenerator(@Value("\${identity.secrets.otp-pepper:}") configuredPepper: String, private val clock: Clock) {

    /**
     * Blank means a random pepper per boot: safe by default, but a restart invalidates TANs in
     * flight and two instances cannot verify each other's. Configure it for multi-instance setups.
     */
    private val pepper: ByteArray = configuredPepper.takeIf { it.isNotBlank() }?.toByteArray()
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    private val random = SecureRandom()
    val validity: Duration = Duration.ofMinutes(5)

    data class Issued(val plainTan: String, val hash: String, val expiresAt: Instant)

    fun issue(): Issued {
        val tan = (random.nextInt(900_000) + 100_000).toString()
        return Issued(tan, hash(tan), clock.instant().plus(validity))
    }

    fun matches(candidate: String, hash: String?, expiresAt: Instant?): Boolean {
        if (hash == null || expiresAt == null) return false
        if (clock.instant().isAfter(expiresAt)) return false
        // Constant-time: `==` on the hex strings leaks how many leading characters matched.
        return MessageDigest.isEqual(hash(candidate.trim()).toByteArray(), hash.toByteArray())
    }

    private fun hash(value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pepper, HMAC_ALGORITHM))
        return Base64.getEncoder().encodeToString(mac.doFinal(value.toByteArray()))
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
