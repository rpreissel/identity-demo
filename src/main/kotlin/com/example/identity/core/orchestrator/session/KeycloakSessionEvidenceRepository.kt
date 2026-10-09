package com.example.identity.core.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Offers no read-modify-write path: two tabs of one session may finish at the same moment, and a
 * load-then-save would let the second overwrite the first. [updateIfYounger] is one statement per method.
 */
@Repository
interface KeycloakSessionEvidenceRepository : JpaRepository<KeycloakSessionEvidence, KeycloakSessionEvidenceId> {

    /**
     * A new row, an INSERT only (see [KeycloakSessionEvidenceInitializer]). H2 runs `MERGE` as a
     * lookup followed by an insert, so two tabs ending at once would both insert; the second insert
     * fails on the key instead and the caller moves on to [updateIfYounger].
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO orchestrator.keycloak_session_evidence
                (kc_session_id, method, account_id, loa, enrolled_under_acr, factor_types, amr_source_id, axis, proven_at, expires_at)
            VALUES (:kcSessionId, :method, :accountId, :loa, :enrolledUnderAcr, :factorTypes, :amrSourceId, :axis, :provenAt, :expiresAt)
        """,
        nativeQuery = true,
    )
    fun insert(
        @Param("kcSessionId") kcSessionId: String,
        @Param("method") method: String,
        @Param("accountId") accountId: Long,
        @Param("loa") loa: String,
        @Param("enrolledUnderAcr") enrolledUnderAcr: String?,
        @Param("factorTypes") factorTypes: String,
        @Param("amrSourceId") amrSourceId: String,
        @Param("axis") axis: String,
        @Param("provenAt") provenAt: Instant,
        @Param("expiresAt") expiresAt: Instant,
    )

    /**
     * Replaces a session's proof of [method] only with a younger one, so an older proof finishing
     * later never pushes a fresh one out. One statement: its row lock orders two tabs ending at once.
     */
    @Modifying
    @Query(
        value = """
            UPDATE orchestrator.keycloak_session_evidence SET
                account_id = :accountId, loa = :loa, enrolled_under_acr = :enrolledUnderAcr,
                factor_types = :factorTypes, amr_source_id = :amrSourceId, axis = :axis, proven_at = :provenAt
            WHERE kc_session_id = :kcSessionId AND method = :method AND proven_at <= :provenAt
        """,
        nativeQuery = true,
    )
    fun updateIfYounger(
        @Param("kcSessionId") kcSessionId: String,
        @Param("method") method: String,
        @Param("accountId") accountId: Long,
        @Param("loa") loa: String,
        @Param("enrolledUnderAcr") enrolledUnderAcr: String?,
        @Param("factorTypes") factorTypes: String,
        @Param("amrSourceId") amrSourceId: String,
        @Param("axis") axis: String,
        @Param("provenAt") provenAt: Instant,
    )

    /** Every row of a session lives as long as the session itself. */
    @Modifying
    @Query("UPDATE KeycloakSessionEvidence e SET e.expiresAt = :expiresAt WHERE e.id.kcSessionId = :kcSessionId")
    fun extend(@Param("kcSessionId") kcSessionId: String, @Param("expiresAt") expiresAt: Instant)

    @Query("SELECT e FROM KeycloakSessionEvidence e WHERE e.id.kcSessionId = :kcSessionId AND e.expiresAt > :now")
    fun findLive(@Param("kcSessionId") kcSessionId: String, @Param("now") now: Instant): List<KeycloakSessionEvidence>

    @Modifying
    @Query("DELETE FROM KeycloakSessionEvidence e WHERE e.id.kcSessionId = :kcSessionId")
    fun deleteBySession(@Param("kcSessionId") kcSessionId: String)

    @Modifying
    @Query("DELETE FROM KeycloakSessionEvidence e WHERE e.accountId = :accountId AND e.id.method = :method")
    fun deleteByAccountAndMethod(@Param("accountId") accountId: Long, @Param("method") method: String)

    @Modifying
    @Query("DELETE FROM KeycloakSessionEvidence e WHERE e.accountId = :accountId")
    fun deleteByAccount(@Param("accountId") accountId: Long)

    @Modifying
    @Query("DELETE FROM KeycloakSessionEvidence e WHERE e.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant): Int
}
