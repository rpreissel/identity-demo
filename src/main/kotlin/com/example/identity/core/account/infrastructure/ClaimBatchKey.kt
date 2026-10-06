package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.jpa.repository.Modifying
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * The data key of one claim batch, wrapped with the account's master key (ADR-52). Deleting this
 * row is how a batch's values are erased: the claim rows stay, readable only as metadata.
 * [expiresAt] is set when the batch is written, so the retention sweep reads nothing else.
 * The id is assigned, not generated, so [isNew] tells `save` to insert without a lookup first.
 */
@Entity
@Table(schema = "account", name = "claim_batch_key")
class ClaimBatchKey(
    @Id
    @Column(name = "claim_batch_id", nullable = false)
    var claimBatchId: UUID? = null,

    @Column(name = "account_id", nullable = false)
    var accountId: AccountId? = null,

    @Column(name = "wrapped_dek", nullable = false)
    var wrappedDek: ByteArray? = null,

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
) : Persistable<UUID> {
    @Transient
    private var persisted = false

    override fun getId(): UUID? = claimBatchId

    override fun isNew(): Boolean = !persisted

    @jakarta.persistence.PostPersist
    @jakarta.persistence.PostLoad
    fun markPersisted() {
        persisted = true
    }
}

@Repository
interface ClaimBatchKeyRepository : JpaRepository<ClaimBatchKey, UUID> {
    fun findByClaimBatchIdAndAccountId(claimBatchId: UUID, accountId: AccountId?): ClaimBatchKey?

    /** One statement, no load: the key is gone whether or not it was in the persistence context. */
    @Modifying
    @Query("delete from ClaimBatchKey k where k.claimBatchId = :claimBatchId")
    fun deleteByClaimBatchId(claimBatchId: UUID): Int

    /** Batches whose retention ended before [cutoff], oldest first - the retention sweep's only query. */
    @Query("select k from ClaimBatchKey k where k.expiresAt < :cutoff order by k.expiresAt")
    fun findExpiredBefore(cutoff: Instant, pageable: Pageable): List<ClaimBatchKey>
}
