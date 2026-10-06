package com.example.identity.core.account.application

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** A wrapped account master key and the KEK version that wrapped it. */
class WrappedKey(val bytes: ByteArray, val kekVersion: String)

/**
 * Wraps and unwraps an account's master key with the key-encryption key (KEK), the top of the key
 * hierarchy (ADR-52). The one place that knows where the KEK lives: a configured secret for the
 * demo, a KMS or HSM in production. Keys below this level never leave the process. The wrapping
 * is not bound to the account id: the key is wrapped before the row, and so the id, exists.
 */
interface MasterKeyWrapper {
    fun wrap(masterKey: ByteArray): WrappedKey

    fun unwrap(wrapped: WrappedKey): ByteArray

    /** Whether the KEK is the one `application.yml` ships for the demo. */
    val usesDemoKek: Boolean
}

/**
 * KEKs that wrapped older master keys: `version -> secret`. A rotated-out KEK stays until every
 * account is re-wrapped under the current one.
 */
@ConfigurationProperties(prefix = "identity.secrets")
data class PreviousMasterKeks(val previousMasterKeks: Map<String, String> = emptyMap())

/**
 * The demo adapter: the KEK is a configured secret, so it lives in the process. Outside demo mode
 * `ProductionModeCheck` refuses the public demo value. Unlike the OTP pepper the KEK must be fixed
 * across restarts, or every stored claim becomes unreadable.
 */
@Component
class ConfiguredKekWrapper(
    @Value("\${identity.secrets.master-kek}") secret: String,
    @Value("\${identity.secrets.master-kek-version:1}") private val currentVersion: String,
    previous: PreviousMasterKeks,
) : MasterKeyWrapper {
    private val current: SecretKey
    private val keks: Map<String, SecretKey>

    override val usesDemoKek: Boolean = secret == DEMO_KEK

    init {
        check(secret.isNotBlank()) { "identity.secrets.master-kek (MASTER_KEK) must not be empty" }
        check(currentVersion !in previous.previousMasterKeks) { "identity.secrets.master-kek-version '$currentVersion' is also listed among the previous KEKs" }
        current = keyFromSecret(secret)
        keks = previous.previousMasterKeks.mapValues { (_, value) -> keyFromSecret(value) } + (currentVersion to current)
    }

    override fun wrap(masterKey: ByteArray): WrappedKey =
        WrappedKey(AesGcm.seal(current, AAD, masterKey), currentVersion)

    override fun unwrap(wrapped: WrappedKey): ByteArray {
        val kek = keks[wrapped.kekVersion]
            ?: error("master key wrapped with KEK version '${wrapped.kekVersion}', for which no secret is configured (identity.secrets.previous-master-keks)")
        return AesGcm.open(kek, AAD, wrapped.bytes)
    }

    /** A 256-bit key from a secret of any length; the secret itself is never used as key material. */
    private fun keyFromSecret(secret: String): SecretKey =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")

    internal companion object {
        /** The default in `application.yml`; `ClaimCryptoTest` keeps the two equal. */
        const val DEMO_KEK = "demo-only-master-kek-not-for-real-people"

        /** What the wrapped bytes are for; a wrapped batch key must not pass as a master key. */
        private val AAD = "account-master-key".toByteArray()
    }
}

/** AES-256-GCM with a fresh 96-bit nonce per message; the nonce leads the sealed bytes. */
internal object AesGcm {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun seal(key: SecretKey, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(key: SecretKey, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > NONCE_BYTES) { "sealed value too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
            updateAAD(aad)
        }
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)
}
