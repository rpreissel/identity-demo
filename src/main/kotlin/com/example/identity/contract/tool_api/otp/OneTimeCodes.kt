package com.example.identity.contract.tool_api.otp

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Six-digit codes scoped to a tool session; the plaintext is never stored. Stored as HMAC-SHA256
 * under a server-side pepper: six digits have only 10^6 preimages, so a plain hash is reversed at
 * once by anyone who can read the column. A module provides it as its own bean, a subclass named
 * for what it issues, so `tool_api` stays without beans (docs/08-projektrahmen.md M-2).
 */
open class OneTimeCodes(configuredPepper: String, private val clock: Clock) {

    /**
     * Blank means a random pepper per boot: safe by default, but a restart invalidates codes in
     * flight and two instances cannot verify each other's. Configure it for multi-instance setups.
     */
    private val pepper: ByteArray = configuredPepper.takeIf { it.isNotBlank() }?.toByteArray()
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    private val random = SecureRandom()
    val validity: Duration = Duration.ofMinutes(5)

    data class Issued(val plain: String, val hash: String, val expiresAt: Instant)

    fun issue(): Issued {
        val code = (random.nextInt(900_000) + 100_000).toString()
        return Issued(code, digest(code), clock.instant().plus(validity))
    }

    fun matches(candidate: String, hash: String?, expiresAt: Instant?): Boolean {
        if (hash == null || expiresAt == null) return false
        if (clock.instant().isAfter(expiresAt)) return false
        // Constant-time: `==` on the strings leaks how many leading characters matched.
        return MessageDigest.isEqual(digest(candidate).toByteArray(), hash.toByteArray())
    }

    /** The stored form of [code], for a module that keeps and compares the digest itself. */
    fun digest(code: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pepper, HMAC_ALGORITHM))
        return Base64.getEncoder().encodeToString(mac.doFinal(code.trim().toByteArray()))
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
