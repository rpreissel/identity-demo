package com.example.identity.core.account

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.application.AesGcm
import com.example.identity.core.account.application.ClaimCrypto
import com.example.identity.core.account.application.MasterKeyWrapper
import com.example.identity.core.account.application.WrappedKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** A data key another module stores, wrapped with the KEK of [kekVersion]. */
data class WrappedDataKey(val bytes: ByteArray, val kekVersion: String)

/**
 * What the key hierarchy of ADR-52 offers other modules. The KEK itself stays behind
 * `MasterKeyWrapper` in this module; a module that keeps its own data keys (the orchestrator's
 * keys per retention class) creates, wraps and uses them here, so one KMS adapter later serves
 * every key in the system and AES-GCM is implemented once.
 */
@Component
class DataKeyWrapping(private val wrapper: MasterKeyWrapper) {
    fun newKey(): ByteArray = AesGcm.newKey()

    fun wrap(key: ByteArray): WrappedDataKey = wrapper.wrap(DATA_KEY_PURPOSE, key).let { WrappedDataKey(it.bytes, it.kekVersion) }

    fun unwrap(wrapped: WrappedDataKey): ByteArray = wrapper.unwrap(DATA_KEY_PURPOSE, WrappedKey(wrapped.bytes, wrapped.kekVersion))

    /** Every KEK version that can still be unwrapped; a stored key under any other version is unreadable. */
    val knownVersions: Set<String> get() = wrapper.knownVersions

    /** AES-256-GCM under [key], with [aad] bound into the ciphertext. */
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = AesGcm.seal(SecretKeySpec(key, "AES"), aad, plaintext)

    fun open(key: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray = AesGcm.open(SecretKeySpec(key, "AES"), aad, sealed)

    private companion object {
        /** Bound into the wrapping, so a data key of another module cannot pass as an account's master key. */
        const val DATA_KEY_PURPOSE = "orchestrator-data-key"
    }
}

/** One account's key, opened once; [purpose] is bound into every ciphertext. */
interface AccountSealer {
    fun seal(purpose: String, plaintext: ByteArray): ByteArray

    fun open(purpose: String, sealed: ByteArray): ByteArray
}

/**
 * Encryption under an account's master key for data that lives and dies with the account, such as
 * the tokens of its app sessions. A value sealed for one purpose cannot be presented as another.
 * Deleting the account makes everything sealed here unreadable. [forAccount] unwraps the master key
 * once; a caller keeps the sealer for the whole operation.
 */
@Component
@Transactional(readOnly = true)
class AccountDataCipher(private val crypto: ClaimCrypto) {
    fun forAccount(accountId: AccountId): AccountSealer = Sealer(crypto.open(accountId))

    private class Sealer(private val cipher: ClaimCrypto.AccountCipher) : AccountSealer {
        override fun seal(purpose: String, plaintext: ByteArray): ByteArray = cipher.seal(purpose, plaintext)

        override fun open(purpose: String, sealed: ByteArray): ByteArray = cipher.open(purpose, sealed)
    }
}
