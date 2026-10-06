package com.example.identity.core.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import org.springframework.data.domain.Persistable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * A data key of one retention class for one day, wrapped with the KEK (ADR-53). Rows written that
 * day name it by [keyId]. After [retireAfter] no row can still need it, and `RetentionJob` deletes
 * it: whatever still lies under it, in a backup or a missed row, is unreadable from then on.
 */
@Entity
@Table(schema = "orchestrator", name = "data_key")
class DataKey(
    @Id
    @Column(name = "key_id", nullable = false, length = 64)
    var keyId: String? = null,

    @Column(name = "retention_class", nullable = false, length = 32)
    var retentionClass: String? = null,

    @Column(name = "wrapped_key", nullable = false)
    var wrappedKey: ByteArray? = null,

    @Column(name = "kek_version", nullable = false, length = 32)
    var kekVersion: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,

    @Column(name = "retire_after", nullable = false)
    var retireAfter: Instant? = null,
) : Persistable<String> {
    @Transient
    private var persisted = false

    override fun getId(): String? = keyId

    override fun isNew(): Boolean = !persisted

    @jakarta.persistence.PostPersist
    @jakarta.persistence.PostLoad
    fun markPersisted() {
        persisted = true
    }
}

@Repository
interface DataKeyRepository : JpaRepository<DataKey, String> {
    @Modifying
    @Query("delete from DataKey k where k.retireAfter < :cutoff")
    fun deleteByRetireAfterBefore(@Param("cutoff") cutoff: Instant): Int

    /** Every KEK version some stored data key is wrapped with - which KEKs are still needed. */
    @Query("select distinct k.kekVersion from DataKey k")
    fun kekVersions(): Set<String>
}
