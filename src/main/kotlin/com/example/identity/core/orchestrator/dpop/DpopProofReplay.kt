package com.example.identity.core.orchestrator.dpop

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * One already-seen proof, keyed by the SHA-256 of `thumbprint:jti`. The primary key is the replay
 * check: a second insert of the same pair fails, with no read-then-write window.
 */
@Entity
@Table(schema = "orchestrator", name = "dpop_proof_replay")
class DpopProofReplay(
    @Id
    @Column(name = "proof_hash", nullable = false, length = 64)
    var proofHash: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
)

interface DpopProofReplayRepository : JpaRepository<DpopProofReplay, String> {
    /**
     * A plain INSERT; the primary key violation on a second call is the replay detection. `save`
     * would merge and let a replayed proof pass silently.
     */
    @Modifying
    @Query(
        value = "INSERT INTO orchestrator.dpop_proof_replay (proof_hash, expires_at) VALUES (:proofHash, :expiresAt)",
        nativeQuery = true,
    )
    fun insert(@Param("proofHash") proofHash: String, @Param("expiresAt") expiresAt: Instant)

    @Modifying
    @Query("delete from DpopProofReplay p where p.expiresAt < :cutoff")
    fun deleteExpired(@Param("cutoff") cutoff: Instant): Int
}
