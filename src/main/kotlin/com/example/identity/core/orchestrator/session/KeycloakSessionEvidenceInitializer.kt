package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Creates a missing row of [KeycloakSessionEvidence] in its own transaction, like
 * [RateLimitRecordInitializer]: a separate bean, because a `REQUIRES_NEW` call inside the same bean
 * would bypass the proxy, and a duplicate-key violation must not poison the caller's transaction.
 * No vendor-specific upsert: H2 rejects `ON CONFLICT`, and its `MERGE` is not atomic.
 */
@Component
class KeycloakSessionEvidenceInitializer(private val repository: KeycloakSessionEvidenceRepository) {

    /**
     * Idempotent. A concurrent creation surfaces as a unique violation, which the caller treats as
     * success: the row exists, and its update takes it from there.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createIfAbsent(kcSessionId: String, accountId: Long, method: MethodEvidenceRow, expiresAt: Instant) {
        if (repository.existsById(KeycloakSessionEvidenceId(kcSessionId, method.method))) return
        repository.insert(
            kcSessionId, method.method, accountId, method.loa, method.enrolledUnderAcr, method.factorTypes,
            method.amrSourceId, method.axis, method.provenAt, expiresAt,
        )
    }
}

/** One method of a channel's evidence as the columns of [KeycloakSessionEvidence] hold it. */
data class MethodEvidenceRow(
    val method: String,
    val loa: String,
    val enrolledUnderAcr: String?,
    val factorTypes: String,
    val amrSourceId: String,
    val axis: String,
    val provenAt: Instant,
) {
    companion object {
        fun of(evidence: MethodEvidence, now: Instant) = MethodEvidenceRow(
            method = evidence.method.value,
            loa = evidence.loa.value,
            enrolledUnderAcr = evidence.enrolledUnderAcr?.value,
            factorTypes = evidence.factorTypes.joinToString(",") { it.name },
            amrSourceId = evidence.amrSourceId,
            axis = evidence.axis.name,
            provenAt = evidence.provenAt ?: now,
        )
    }
}
