package com.example.identity.tools.auth_kobil.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The two secrets this module mints for a device. The PIN is KOBIL's; the backend keeps it and
 * hands it out per run, so it must be stored recoverably (ADR-22). The unlock secret is ours, kept
 * by the app behind its biometric prompt. With 256 bits of entropy a plain SHA-256 suffices: a
 * password KDF only defends a small preimage space.
 */
@Component
class KobilSecrets(
    @Value("\${identity.kobil.pin-length:8}") private val pinLength: Int,
) {

    private val random = SecureRandom()

    fun newPin(): String = (1..pinLength).map { random.nextInt(10) }.joinToString("")

    fun newUnlockSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(unlockSecret: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(unlockSecret.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Constant-time, so a wrong secret costs the same as a right one, and a missing [storedHash]
     * (no biometric consent) costs the same again.
     */
    fun matches(candidate: String, storedHash: String?): Boolean {
        val expected = storedHash ?: NO_CONSENT
        return MessageDigest.isEqual(hash(candidate).toByteArray(), expected.toByteArray()) && storedHash != null
    }

    private companion object {
        /** Never equal to a real digest (hex has no dashes), so the comparison can only fail. */
        const val NO_CONSENT = "-no-biometric-consent-"
    }
}
