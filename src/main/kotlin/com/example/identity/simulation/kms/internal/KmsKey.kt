package com.example.identity.simulation.kms.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Entity
@Table(schema = "kms", name = "transit_key")
class KmsKey(
    @Id
    @Column(name = "name", nullable = false, length = 64)
    var name: String? = null,

    @Column(name = "key_type", nullable = false, length = 16)
    var keyType: String? = null,

    @Column(name = "latest_version", nullable = false)
    var latestVersion: Int = 0,

    @Column(name = "min_decryption_version", nullable = false)
    var minDecryptionVersion: Int = 1,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,
)

data class KmsKeyVersionId(var keyName: String? = null, var version: Int = 0) : Serializable

@Entity
@IdClass(KmsKeyVersionId::class)
@Table(schema = "kms", name = "transit_key_version")
class KmsKeyVersion(
    @Id
    @Column(name = "key_name", nullable = false, length = 64)
    var keyName: String? = null,

    @Id
    @Column(name = "version", nullable = false)
    var version: Int = 0,

    /** The secret itself: an AES key, or a PKCS#8-encoded EC private key. Never leaves the simulation. */
    @Column(name = "material", nullable = false)
    var material: ByteArray? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,
)

@Repository
interface KmsKeyRepository : JpaRepository<KmsKey, String>

@Repository
interface KmsKeyVersionRepository : JpaRepository<KmsKeyVersion, KmsKeyVersionId> {
    fun findByKeyNameOrderByVersion(keyName: String): List<KmsKeyVersion>
}
