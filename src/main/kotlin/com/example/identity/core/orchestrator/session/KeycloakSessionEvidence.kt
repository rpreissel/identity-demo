package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Embeddable
data class KeycloakSessionEvidenceId(
    @Column(name = "kc_session_id", nullable = false, length = 64)
    var kcSessionId: String? = null,

    @Column(name = "method", nullable = false, length = 64)
    var method: String? = null
) : Serializable

/**
 * One method a Keycloak session has proven (ADR-59): a new Web channel of the same session takes
 * these rows over instead of proving them again. One row per session and method, so two tabs that
 * finish at once write different rows and neither overwrites the other. Written only by
 * [KeycloakSessionEvidenceRepository.upsert], read and deleted here.
 */
@Entity
@Table(schema = "orchestrator", name = "keycloak_session_evidence")
class KeycloakSessionEvidence(
    @EmbeddedId
    var id: KeycloakSessionEvidenceId? = null
) {
    @Column(name = "account_id", nullable = false)
    var accountId: Long = 0

    @Column(name = "loa", nullable = false, length = 16)
    var loa: String = ""

    @Column(name = "enrolled_under_acr", length = 16)
    var enrolledUnderAcr: String? = null

    /** [FactorType] names, comma-separated. */
    @Column(name = "factor_types", nullable = false, length = 128)
    var factorTypes: String = ""

    @Column(name = "amr_source_id", nullable = false, length = 64)
    var amrSourceId: String = ""

    @Column(name = "axis", nullable = false, length = 32)
    var axis: String = EvidenceAxis.AUTHENTICATOR.name

    @Column(name = "proven_at", nullable = false)
    var provenAt: Instant = Instant.EPOCH

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH

    /** The proof this row stands for, with its original age. */
    fun toMethodEvidence(): MethodEvidence = MethodEvidence(
        method = MethodName(checkNotNull(id?.method)),
        loa = AcrLevel.parse(loa) ?: AcrLevel.NONE,
        enrolledUnderAcr = enrolledUnderAcr?.let(AcrLevel::parse),
        factorTypes = factorTypes.split(",").filter { it.isNotEmpty() }.map(FactorType::valueOf).toSet(),
        amrSourceId = amrSourceId,
        axis = EvidenceAxis.valueOf(axis),
        provenAt = provenAt,
    )
}
