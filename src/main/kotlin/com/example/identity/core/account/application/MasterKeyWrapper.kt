package com.example.identity.core.account.application

import com.example.identity.contract.tool_api.kms.KeyService
import com.example.identity.contract.tool_api.kms.KmsKeyType
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
    /** [purpose] is bound into the wrapping: a key wrapped as one thing cannot be presented as another. */
    fun wrap(purpose: String, masterKey: ByteArray): WrappedKey

    fun unwrap(purpose: String, wrapped: WrappedKey): ByteArray

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
class KmsKekWrapper(private val kms: KeyService) : MasterKeyWrapper {
    init {
        kms.ensureKey(KEK_KEY, KmsKeyType.AES256)
    }

    override fun wrap(purpose: String, masterKey: ByteArray): WrappedKey =
        kms.encrypt(KEK_KEY, purpose, masterKey).let { WrappedKey(it.bytes, "v${it.keyVersion}") }

    override fun unwrap(purpose: String, wrapped: WrappedKey): ByteArray = kms.decrypt(KEK_KEY, versionOf(wrapped.kekVersion), purpose, wrapped.bytes)

    override val knownVersions: Set<String> get() = checkNotNull(kms.findKey(KEK_KEY)).usableVersions.map { "v$it" }.toSet()

    override val simulated: Boolean get() = kms.simulated

    /** `v<N>` only: a row from before ADR-54 carries a bare number, which no KMS version answers for. */
    private fun versionOf(kekVersion: String): Int =
        kekVersion.takeIf { it.startsWith("v") }?.drop(1)?.toIntOrNull()
            ?: error("'$kekVersion' is not a KMS key version (v<N>) - the row was wrapped before the key service existed (ADR-54)")

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
