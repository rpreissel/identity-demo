package com.example.identity.simulation.kms

import com.example.identity.contract.tool_api.kms.KeyService
import com.example.identity.contract.tool_api.kms.KmsKeyInfo
import com.example.identity.contract.tool_api.kms.KmsKeyType
import com.example.identity.contract.tool_api.kms.KmsVersioned
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

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
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : KeyService {
    private val random = SecureRandom()
    // Creation commits on its own: a key must never be created inside a caller's transaction, which
    // may go on to wait for a network peer while holding the row lock every other caller runs into.
    private val newTransaction = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    override val simulated: Boolean = true

    /** Two instances creating the same key at once: the loser takes the winner's key. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun ensureKey(name: String, type: KmsKeyType): KmsKeyInfo {
        findKey(name)?.let { existing ->
            check(existing.type == type) { "KMS key '$name' is ${existing.type}, not $type" }
            return existing
        }
        return try {
            newTransaction.execute {
                val key = keys.save(KmsKey(name = name, keyType = type.name, latestVersion = 0, createdAt = clock.instant()))
                addVersion(key, type)
                infoOf(key)
            }!!
        } catch (e: DataIntegrityViolationException) {
            checkNotNull(findKey(name)) { "KMS key '$name' was created concurrently but cannot be read" }
        }
    }

    @Transactional(readOnly = true)
    override fun latestVersion(name: String): Int = existing(name).latestVersion

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
        require(version in key.minDecryptionVersion..key.latestVersion) {
            "version $version is not between the retired floor ${key.minDecryptionVersion} and the latest ${key.latestVersion} of key '$name'"
        }
        key.minDecryptionVersion = version
        versions.findByKeyNameOrderByVersion(name).filter { it.version < version }.forEach(versions::delete)
        return infoOf(keys.save(key))
    }

    @Transactional(readOnly = true)
    fun keyInfo(name: String): KmsKeyInfo = infoOf(existing(name))

    @Transactional(readOnly = true)
    override fun findKey(name: String): KmsKeyInfo? = keys.findById(name).orElse(null)?.let(::infoOf)

    @Transactional(readOnly = true)
    fun allKeys(): List<KmsKeyInfo> = keys.findAll().sortedBy { it.name }.map(::infoOf)

    /** [plaintext] under the latest version of the AES key [name], [context] as checked data; the nonce leads the bytes. */
    @Transactional(readOnly = true)
    override fun encrypt(name: String, context: String, plaintext: ByteArray): KmsVersioned {
        val key = existing(name, KmsKeyType.AES256)
        val version = key.latestVersion
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, aesKey(name, version), GCMParameterSpec(128, nonce))
            updateAAD(context.toByteArray())
        }
        return KmsVersioned(version, nonce + cipher.doFinal(plaintext))
    }

    @Transactional(readOnly = true)
    override fun decrypt(name: String, keyVersion: Int, context: String, ciphertext: ByteArray): ByteArray {
        val key = existing(name, KmsKeyType.AES256)
        check(keyVersion >= key.minDecryptionVersion) { "KMS key '$name' version $keyVersion is retired" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, aesKey(name, keyVersion), GCMParameterSpec(128, ciphertext, 0, NONCE_BYTES))
            updateAAD(context.toByteArray())
        }
        return cipher.doFinal(ciphertext, NONCE_BYTES, ciphertext.size - NONCE_BYTES)
    }

    /** ECDSA over SHA-256 of [input] with the latest version of [name], DER-encoded; a JWS caller transcodes it. */
    @Transactional(readOnly = true)
    override fun sign(name: String, input: ByteArray): KmsVersioned {
        val key = existing(name, KmsKeyType.ECDSA_P256)
        val version = key.latestVersion
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(ecPair(name, version).first); update(input) }
        return KmsVersioned(version, signature.sign())
    }

    /** The public half of version [version] - the only thing of a signing key that leaves the service. */
    @Transactional(readOnly = true)
    override fun publicKey(name: String, version: Int): ECPublicKey {
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
