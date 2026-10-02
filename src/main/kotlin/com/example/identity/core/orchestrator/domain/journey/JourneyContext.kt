package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag

/**
 * Everything a strategy may look at. Read-only by construction: [policy] and [catalog] answer
 * questions, they change nothing.
 */
data class JourneyContext(
    /**
     * Which facade opened this channel (docs/02-domaenenmodell.md #4). A declared fact, so a strategy
     * never infers the channel type from [bindingKeyRef] being null.
     */
    val channel: ChannelType,
    /** The account this journey concerns, `null` before any identification or lookup. */
    val account: AccountProfile?,
    /** What this channel's session has already proven. */
    val evidence: SessionEvidence,
    /** The channel's durable lower bound, not a single run's target (that lives in the state). */
    val acrFloor: AcrLevel,
    /** The calling device's DPoP-proven key thumbprint; null on a Web channel. */
    val bindingKeyRef: String?,
    /** The account this device is durably linked to, if any, independent of this channel. */
    val linkedAccountId: AccountId?,
    /** True while this journey runs as another one's precondition (docs/04-orchestrierung.md #6). */
    val isSubJourney: Boolean,
    /** Answers ACR and candidate questions. */
    val policy: AuthPolicy,
    /** The full tool catalog, for descriptor lookups. */
    val catalog: ToolCatalog,
    /**
     * toolIds this channel may offer now: the client's declared support minus what the backend
     * switched off (docs/03-tool-architektur.md). Never derive an offer from [catalog] alone.
     */
    val availableTools: Set<ToolId>,
    /**
     * Runtime feature flags currently enabled ([JourneyFeatureFlag] names them). Resolved here because a
     * strategy never depends on a `@Service` itself.
     */
    val featureFlags: Set<JourneyFeatureFlag> = emptySet(),
    /**
     * The invitation this channel signed in with instead of an account
     * (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Never set together with [account].
     */
    val invitation: InvitationId? = null
) {
    /**
     * The resolved account, for states that cannot be reached without one (step-up, method change,
     * deletion). There a missing account is a broken state machine, so this crashes.
     */
    fun requireAccount(): AccountProfile =
        checkNotNull(account) { "Strategy asked for an account before one was resolved" }

    /** The ACR this channel's evidence resolves to right now, e.g. a sub-journey's `startingAcr`. */
    val currentAcr: AcrLevel get() = policy.resolveAcr(evidence, account)
}
