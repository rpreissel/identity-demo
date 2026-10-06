package com.example.identity.simulation.kms

import com.example.identity.simulation.kms.internal.KmsKey
import com.example.identity.simulation.kms.internal.KmsKeyRepository
import com.example.identity.simulation.kms.internal.KmsKeyVersion
import com.example.identity.simulation.kms.internal.KmsKeyVersionId
import com.example.identity.simulation.kms.internal.KmsKeyVersionRepository
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** What this KMS does with a key. */
enum class KmsKeyType { AES256, ECDSA_P256 }

/** A key as the caller sees it: versions, never material. */
data class KmsKeyInfo(val name: String, val type: KmsKeyType, val latestVersion: Int, val minDecryptionVersion: Int, val versions: List<Int>) {
    /** The versions that still decrypt and verify. */
    val usableVersions: List<Int> get() = versions.filter { it >= minDecryptionVersion }
}

/** Bytes a key version produced, with that version, so the caller can store both and ask again later. */
class KmsVersioned(val keyVersion: Int, val bytes: ByteArray)

/**
 * The backend-facing face of the simulated KMS, shaped like Vault's Transit engine: named keys with
 * versions, encrypt and decrypt under an AES key, sign under an EC key, rotate, retire. Key material
 * is read only here. A caller stores ciphertext or signature together with the key version; a
 * version below `minDecryptionVersion` no longer decrypts or verifies, which is how a key retires.
 */
@Service
@Transactional
class KmsTransit(
    private val keys: KmsKeyRepository,
    private val versions: KmsKeyVersionRepository,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    /**
     * The key, created with its first version if it does not exist yet. Always its own transaction:
     * a key must never be created inside a caller's transaction, which may go on to wait for a
     * network peer while holding the row lock every other caller then runs into.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureKey(name: String, type: KmsKeyType): KmsKeyInfo {
        val key = keys.findById(name).orElse(null)
            ?: keys.save(KmsKey(name = name, keyType = type.name, latestVersion = 0, createdAt = clock.instant())).also { addVersion(it, type) }
        check(key.keyType == type.name) { "KMS key '$name' is ${key.keyType}, not $type" }
        return infoOf(key)
    }

    /** A new version; from now on it encrypts and signs, the older ones still decrypt and verify. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun rotate(name: String): KmsKeyInfo {
        val key = existing(name)
        addVersion(key, KmsKeyType.valueOf(checkNotNull(key.keyType)))
        return infoOf(key)
    }

    /** Retires every version below [version]: it decrypts and verifies no more, and its material is destroyed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retireBelow(name: String, version: Int): KmsKeyInfo {
        val key = existing(name)
        require(version in 1..key.latestVersion) { "version $version is not one of key '$name'" }
        key.minDecryptionVersion = version
        versions.findByKeyNameOrderByVersion(name).filter { it.version < version }.forEach(versions::delete)
        return infoOf(keys.save(key))
    }

    @Transactional(readOnly = true)
    fun keyInfo(name: String): KmsKeyInfo = infoOf(existing(name))

    /** [keyInfo] for a key that may not exist yet, without creating it. */
    @Transactional(readOnly = true)
    fun findKey(name: String): KmsKeyInfo? = keys.findById(name).orElse(null)?.let(::infoOf)

    @Transactional(readOnly = true)
    fun allKeys(): List<KmsKeyInfo> = keys.findAll().sortedBy { it.name }.map(::infoOf)

    /** [plaintext] under the latest version of the AES key [name]; the nonce leads the bytes. */
    @Transactional(readOnly = true)
    fun encrypt(name: String, plaintext: ByteArray): KmsVersioned {
        val key = existing(name, KmsKeyType.AES256)
        val version = key.latestVersion
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey(name, version), GCMParameterSpec(128, nonce)) }
        return KmsVersioned(version, nonce + cipher.doFinal(plaintext))
    }

    @Transactional(readOnly = true)
    fun decrypt(name: String, keyVersion: Int, ciphertext: ByteArray): ByteArray {
        val key = existing(name, KmsKeyType.AES256)
        check(keyVersion >= key.minDecryptionVersion) { "KMS key '$name' version $keyVersion is retired" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, aesKey(name, keyVersion), GCMParameterSpec(128, ciphertext, 0, NONCE_BYTES))
        }
        return cipher.doFinal(ciphertext, NONCE_BYTES, ciphertext.size - NONCE_BYTES)
    }

    /** ECDSA over SHA-256 of [input] with the latest version of [name], DER-encoded; a JWS caller transcodes it. */
    @Transactional(readOnly = true)
    fun sign(name: String, input: ByteArray): KmsVersioned {
        val key = existing(name, KmsKeyType.ECDSA_P256)
        val version = key.latestVersion
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(ecPair(name, version).first); update(input) }
        return KmsVersioned(version, signature.sign())
    }

    /** The public half of version [version] - the only thing of a signing key that leaves the service. */
    @Transactional(readOnly = true)
    fun publicKey(name: String, version: Int): ECPublicKey {
        val key = existing(name, KmsKeyType.ECDSA_P256)
        check(version >= key.minDecryptionVersion) { "KMS key '$name' version $version is retired" }
        return ecPair(name, version).second
    }

    private fun addVersion(key: KmsKey, type: KmsKeyType) {
        val version = key.latestVersion + 1
        val material = when (type) {
            KmsKeyType.AES256 -> ByteArray(32).also(random::nextBytes)
            KmsKeyType.ECDSA_P256 -> {
                val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
                val private = pair.private.encoded
                val public = pair.public.encoded
                ByteBuffer.allocate(2 + private.size + public.size).putShort(private.size.toShort()).put(private).put(public).array()
            }
        }
        versions.save(KmsKeyVersion(keyName = key.name, version = version, material = material, createdAt = clock.instant()))
        key.latestVersion = version
        keys.save(key)
    }

    private fun existing(name: String, type: KmsKeyType? = null): KmsKey {
        val key = keys.findById(name).orElse(null) ?: error("KMS key '$name' does not exist")
        type?.let { check(key.keyType == it.name) { "KMS key '$name' is ${key.keyType}, not $it" } }
        return key
    }

    private fun material(name: String, version: Int): ByteArray =
        checkNotNull(versions.findById(KmsKeyVersionId(name, version)).orElse(null)?.material) { "KMS key '$name' has no version $version" }

    private fun aesKey(name: String, version: Int) = SecretKeySpec(material(name, version), "AES")

    private fun ecPair(name: String, version: Int): Pair<ECPrivateKey, ECPublicKey> {
        val buffer = ByteBuffer.wrap(material(name, version))
        val private = ByteArray(buffer.short.toInt()).also(buffer::get)
        val public = ByteArray(buffer.remaining()).also(buffer::get)
        val factory = KeyFactory.getInstance("EC")
        return factory.generatePrivate(PKCS8EncodedKeySpec(private)) as ECPrivateKey to factory.generatePublic(X509EncodedKeySpec(public)) as ECPublicKey
    }

    private fun infoOf(key: KmsKey) = KmsKeyInfo(
        name = checkNotNull(key.name), type = KmsKeyType.valueOf(checkNotNull(key.keyType)), latestVersion = key.latestVersion,
        minDecryptionVersion = key.minDecryptionVersion, versions = versions.findByKeyNameOrderByVersion(checkNotNull(key.name)).map { it.version }
    )

    private companion object {
        const val NONCE_BYTES = 12
    }
}
