package com.example.identity.contract.tool_api.kms

import java.security.interfaces.ECPublicKey

/** What a key service does with a key. */
enum class KmsKeyType { AES256, ECDSA_P256 }

/** A key as the caller sees it: versions, never material. */
data class KmsKeyInfo(val name: String, val type: KmsKeyType, val latestVersion: Int, val minDecryptionVersion: Int, val versions: List<Int>) {
    /** The versions that still decrypt and verify. */
    val usableVersions: List<Int> get() = versions.filter { it >= minDecryptionVersion }
}

/** Bytes a key version produced, with that version, so the caller can store both and ask again later. */
class KmsVersioned(val keyVersion: Int, val bytes: ByteArray)

/**
 * The port to the key management service (ADR-54): named keys with versions that never leave the
 * service. Shaped like Vault's Transit engine, so an adapter for Vault, a cloud KMS or an HSM maps
 * one to one. [context] binds a ciphertext to its purpose, as Vault's `context` does: a value
 * wrapped as one thing cannot be presented as another. The demo implements it in `simulation/kms`.
 */
interface KeyService {
    /** Creates the key with its first version if it does not exist; safe to call from several instances at once. */
    fun ensureKey(name: String, type: KmsKeyType): KmsKeyInfo

    /** The key, or `null` if it does not exist yet. */
    fun findKey(name: String): KmsKeyInfo?

    /** The version that encrypts and signs now - one cheap read. */
    fun latestVersion(name: String): Int

    /** [plaintext] under the latest version of the AES key [name], bound to [context]. */
    fun encrypt(name: String, context: String, plaintext: ByteArray): KmsVersioned

    fun decrypt(name: String, keyVersion: Int, context: String, ciphertext: ByteArray): ByteArray

    /** ECDSA over SHA-256 of [input] with the latest version of [name], DER-encoded. */
    fun sign(name: String, input: ByteArray): KmsVersioned

    /** The public half of version [version] - the only thing of a signing key that leaves the service. */
    fun publicKey(name: String, version: Int): ECPublicKey

    /** Whether this is the demo's simulation rather than a real service; `ProductionModeCheck` refuses the simulation. */
    val simulated: Boolean
}
