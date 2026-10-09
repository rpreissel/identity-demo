package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
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

/**
 * The persisted evidence record, kept apart from the tokens issued from it (ADR-15). One per
 * channel, cleared at logout; continuity across flow runs is the Keycloak facade's `RestoreData`. The
 * persisted form of [com.example.identity.core.orchestrator.domain.policy.SessionEvidence]. `currentAcr` is no
 * field: every reader recomputes it with `AuthPolicy.resolveAcr`.
 */
@Entity
@Table(schema = "orchestrator", name = "session_evidence")
class SessionEvidenceRecord(
    subject: Subject,
    now: Instant,
) {
    /** The account of [subject], if the subject is one. Written only through [subject]. */
    @Column(name = "account_id")
    var accountId: AccountId? = null
        protected set

    /** The invitation of [subject], if the subject is one. Written only through [subject]. */
    @Column(name = "invitation", length = 64)
    var invitation: InvitationId? = null
        protected set

    /**
     * Whom this evidence belongs to: an account, or an invitation
     * (ADR-48 (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)). Always one of them; the
     * database holds that (`ck_session_evidence_one_subject`).
     */
    var subject: Subject
        get() = accountId?.let(Subject::Account) ?: Subject.Invitation(checkNotNull(invitation))
        set(value) {
            accountId = (value as? Subject.Account)?.id
            invitation = (value as? Subject.Invitation)?.id
        }

    init {
        this.subject = subject
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var sessionEvidenceId: SessionEvidenceId? = null

    /**
     * One record per method proven in this channel (docs/04-orchestrierung.md #4). One JSON column
     * of records rather than parallel Method->X columns, so a method cannot appear in one but not
     * another. The properties below are derived views over it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "methods", nullable = false)
    var methods: MutableList<MethodEvidenceRecord> = mutableListOf()

    /** Every method proven so far. */
    val currentAmr: List<String> get() = methods.map { it.method }

    /**
     * Each entry's loa (docs/05-api.md Abschnitt 3b), the only figure `AuthPolicy.resolveAcr` prices
     * from, whether an orchestrator tool proved it here or it was restored from Keycloak's session.
     */
    val methodAcr: Map<String, String> get() = methods.associate { it.method to it.loa }

    /**
     * Each entry's ceiling for an MFA combination (docs/06-ablaeufe.md #1). Supplied by whoever
     * recorded the entry, never derived here.
     */
    val enrolledUnderAcr: Map<String, String> get() = methods.mapNotNull { r -> r.enrolledUnderAcr?.let { r.method to it } }.toMap()

    /**
     * The factor kinds of all entries. Kept per method, since amr values name procedures, not
     * factor kinds (docs/02-domaenenmodell.md #5).
     */
    val currentFactorTypes: Set<FactorType> get() = methods.flatMap { it.factorTypes }.toSet()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = now

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    /**
     * Merge: adds or updates the given methods, never removes one. Used for the proof of a single
     * completed tool and for restored evidence. [factorTypes] stay unioned over the trail.
     */
    fun addAmr(updates: List<MethodEvidence>, now: Instant) {
        for (update in updates) {
            val method = update.method.value
            val existing = methods.find { it.method == method }
            val record = MethodEvidenceRecord(
                method = method,
                loa = update.loa.value,
                enrolledUnderAcr = update.enrolledUnderAcr?.value,
                factorTypes = (existing?.factorTypes ?: emptySet()) + update.factorTypes,
                amrSourceId = update.amrSourceId,
                axis = update.axis,
                // A restored proof keeps its age; anything else was proven just now.
                provenAt = update.provenAt ?: now,
            )
            methods = (methods.filterNot { it.method == method } + record).toMutableList()
        }
        updatedAt = now
    }
}

/** One method's record within [SessionEvidenceRecord.methods]. */
data class MethodEvidenceRecord(
    val method: String,
    val loa: String,
    val enrolledUnderAcr: String? = null,
    val factorTypes: Set<FactorType> = emptySet(),
    /** Mirrors `SessionEvidence.MethodEvidence.amrSourceId`. */
    val amrSourceId: String,
    /** Mirrors `SessionEvidence.MethodEvidence.axis` ([EvidenceAxis]). Defaulted for rows without it. */
    val axis: EvidenceAxis = EvidenceAxis.AUTHENTICATOR,
    /** Mirrors `SessionEvidence.MethodEvidence.provenAt`. Rows from before it count as of unknown age. */
    val provenAt: Instant? = null,
)

/**
 * Rebuilds the [MethodEvidence] this record represents, including `amrSourceId`,
 * which `SessionEvidence.from(...)` defaults away. Every reader of a stored [SessionEvidenceRecord] goes
 * through here.
 */
fun MethodEvidenceRecord.toMethodEvidence(): MethodEvidence =
    MethodEvidence(MethodName(method), AcrLevel.parse(loa) ?: AcrLevel.NONE, enrolledUnderAcr?.let(AcrLevel::parse), factorTypes, amrSourceId, axis, provenAt)

/** The core [SessionEvidence] this channel's evidence currently is. */
fun SessionEvidenceRecord.toCoreEvidence(): SessionEvidence = SessionEvidence(methods.map { it.toMethodEvidence() })
