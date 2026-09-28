package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID
import com.example.identity.core.orchestrator.domain.AmrSource

/**
 * The persisted evidence record, kept apart from the tokens issued from it (ADR-15). One per
 * channel, cleared at logout; continuity across flow runs is the kc facade's `RestoreData`. The
 * persisted form of [com.example.identity.core.orchestrator.domain.policy.AuthEvidence]. `currentAcr` is no
 * field: every reader recomputes it with `AuthPolicy.resolveAcr`.
 */
@Entity
@Table(schema = "orchestrator", name = "auth_evidence")
class EvidenceTrail(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var authEvidenceId: UUID? = null

    /**
     * One record per method proven in this channel (docs/04-orchestrierung.md #1). One JSON column
     * of records rather than parallel Method->X columns, so a method cannot appear in one but not
     * another. The properties below are derived views over it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "amr_evidence", nullable = false)
    var amrEvidence: MutableList<AmrRecord> = mutableListOf()

    /** Every method proven so far. */
    val currentAmr: List<String> get() = amrEvidence.map { it.method }

    /**
     * Who proved each entry in [currentAmr]: [AmrSource.ORCHESTRATOR] or [AmrSource.KEYCLOAK]
     * (docs/05-api.md Abschnitt 3). Exposed via `AuthData.amr` on KEYCLOAK channels for
     * information only; the orchestrator alone resolves the combined `acr`.
     */
    val currentAmrSource: Map<String, String> get() = amrEvidence.associate { it.method to it.source }

    /**
     * Each entry's loa (docs/05-api.md Abschnitt 3), the only figure `AuthPolicy.resolveAcr` prices
     * from, whether it came from an orchestrator tool or a native Keycloak authenticator.
     */
    val methodAcr: Map<String, String> get() = amrEvidence.associate { it.method to it.loa }

    /**
     * Each entry's ceiling for an MFA combination (docs/06-ablaeufe.md #1). Supplied by whoever
     * recorded the entry, never derived here.
     */
    val enrolledUnderAcr: Map<String, String> get() = amrEvidence.mapNotNull { r -> r.enrolledUnderAcr?.let { r.method to it } }.toMap()

    /**
     * The factor kinds of all entries. Kept per method, since amr values name procedures, not
     * factor kinds (docs/02-domaenenmodell.md #5).
     */
    val currentFactorTypes: Set<FactorType> get() = amrEvidence.flatMap { it.factorTypes }.toSet()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        updatedAt = Instant.now()
    }

    /**
     * Merge: adds or updates the given methods, never removes one. Used for the proof of a single
     * completed tool. Per entry, an orchestrator proof upgrades a method last reported by Keycloak,
     * whose report the orchestrator cannot verify (ADR-7). The reverse never happens. [factorTypes]
     * stay unioned over the trail.
     */
    fun addAmr(updates: List<MethodEvidence>) {
        for (update in updates) {
            val method = update.method.value
            val existing = amrEvidence.find { it.method == method }
            val newSource = if (existing == null || existing.source == AmrSource.KEYCLOAK) update.source else existing.source
            val record = AmrRecord(
                method = method,
                source = newSource,
                loa = update.loa.value,
                enrolledUnderAcr = update.enrolledUnderAcr?.value,
                factorTypes = (existing?.factorTypes ?: emptySet()) + update.factorTypes,
                amrSourceId = update.amrSourceId,
                axis = update.axis,
            )
            amrEvidence = (amrEvidence.filterNot { it.method == method } + record).toMutableList()
        }
        updatedAt = Instant.now()
    }

    /**
     * Sync, not merge: [updates] is the caller's complete currently valid set for [source]
     * (docs/05-api.md Abschnitt 3). A record owned by [source] whose method is missing has expired
     * and is dropped. Records owned by another source stay, so Keycloak never downgrades an
     * orchestrator proof. The rest is upserted as in [addAmr].
     */
    fun replaceForSource(source: String, updates: List<MethodEvidence>) {
        val stillValid = updates.map { it.method.value }.toSet()
        val expired = amrEvidence.filter { it.source == source && it.method !in stillValid }
        if (expired.isNotEmpty()) {
            amrEvidence = amrEvidence.filterNot { it in expired }.toMutableList()
        }
        addAmr(updates)
    }
}

/** One method's record within [EvidenceTrail.amrEvidence]. */
data class AmrRecord(
    val method: String,
    val source: String,
    val loa: String,
    val enrolledUnderAcr: String? = null,
    val factorTypes: Set<FactorType> = emptySet(),
    /** Mirrors `AuthEvidence.MethodEvidence.amrSourceId`. */
    val amrSourceId: String,
    /** Mirrors `AuthEvidence.MethodEvidence.axis` ([EvidenceAxis]). Defaulted for rows without it. */
    val axis: EvidenceAxis = EvidenceAxis.AUTHENTICATOR,
)

/**
 * Rebuilds the [MethodEvidence] this record represents, including `source` and `amrSourceId`,
 * which `AuthEvidence.from(...)` defaults away. Every reader of a stored [EvidenceTrail] goes
 * through here.
 */
fun AmrRecord.toMethodEvidence(): MethodEvidence =
    MethodEvidence(MethodName(method), AcrLevel.of(loa), enrolledUnderAcr?.let(AcrLevel::of), factorTypes, source, amrSourceId, axis)

/** The core [AuthEvidence] this channel's evidence currently is. */
fun EvidenceTrail.toCoreEvidence(): AuthEvidence = AuthEvidence(amrEvidence.map { it.toMethodEvidence() })
