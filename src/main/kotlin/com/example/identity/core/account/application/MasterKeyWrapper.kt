package com.example.identity.core.account.application

import com.example.identity.simulation.kms.KmsKeyType
import com.example.identity.simulation.kms.KmsTransit
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.springframework.stereotype.Component

/** A wrapped account master key and the KEK version that wrapped it. */
class WrappedKey(val bytes: ByteArray, val kekVersion: String)

/**
 * Wraps and unwraps an account's master key with the key-encryption key (KEK), the top of the key
 * hierarchy (ADR-52). The one place that knows where the KEK lives: a key management service that
 * never hands it out. Keys below this level never leave the process. The wrapping is not bound to
 * the account id: the key is wrapped before the row, and so the id, exists.
 */
interface MasterKeyWrapper {
    fun wrap(masterKey: ByteArray): WrappedKey

    fun unwrap(wrapped: WrappedKey): ByteArray

    /** Every KEK version this wrapper can still unwrap - the current one and each not yet retired. */
    val knownVersions: Set<String>

    /** Whether the service behind the KEK is the demo's simulation rather than a real KMS or HSM. */
    val simulated: Boolean
}

/**
 * The KEK as a key in the KMS (ADR-54): `identity-kek`, created on first use. The KMS returns its
 * key version with every ciphertext; stored as `v<N>` next to the wrapped key, it tells which
 * version to ask for later. Rotating the KEK in the KMS needs nothing here: new wraps take the
 * new version, old ones keep unwrapping until the KMS retires their version.
 */
@Component
class KmsKekWrapper(private val kms: KmsTransit) : MasterKeyWrapper {
    init {
        kms.ensureKey(KEK_KEY, KmsKeyType.AES256)
    }

    override fun wrap(masterKey: ByteArray): WrappedKey = kms.encrypt(KEK_KEY, masterKey).let { WrappedKey(it.bytes, "v${it.keyVersion}") }

    override fun unwrap(wrapped: WrappedKey): ByteArray = kms.decrypt(KEK_KEY, versionOf(wrapped.kekVersion), wrapped.bytes)

    override val knownVersions: Set<String> get() = kms.keyInfo(KEK_KEY).usableVersions.map { "v$it" }.toSet()

    override val simulated: Boolean = true

    private fun versionOf(kekVersion: String): Int =
        kekVersion.removePrefix("v").toIntOrNull() ?: error("not a KMS key version: '$kekVersion'")

    companion object {
        const val KEK_KEY = "identity-kek"
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
