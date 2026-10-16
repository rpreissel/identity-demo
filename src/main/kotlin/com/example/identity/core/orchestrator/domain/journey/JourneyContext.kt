package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag

/**
 * Everything a journey may look at. Read-only by construction: [policy] and [catalog] answer
 * questions, they change nothing.
 */
data class JourneyContext(
    /**
     * Which facade opened this channel (docs/02-domaenenmodell.md #4). A declared fact, so a journey
     * never infers the channel type from [bindingKeyRef] being null.
     */
    val channel: ChannelType,
    /** Who this channel is signing in: nobody yet, an account, or a process access. */
    val signingIn: SigningIn,
    /** What this channel's session has already proven. */
    val evidence: SessionEvidence,
    /** The channel's durable lower bound, not a single run's target (that lives in the state). */
    val acrFloor: AcrLevel,
    /** The calling device's DPoP-proven key thumbprint; null on a Web channel. */
    val bindingKeyRef: String?,
    /** The account this device is durably linked to, if any, independent of this channel. */
    val linkedAccountId: AccountId?,
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
     * Runtime feature flags enabled when this journey started ([JourneyFeatureFlag] names them). Resolved here because a
     * journey never depends on a `@Service` itself.
     */
    val featureFlags: Set<JourneyFeatureFlag> = emptySet()
) {
    /** The account this journey concerns; `null` before any identification or lookup, and for a process access. */
    val account: AccountProfile? get() = (signingIn as? SigningIn.Holder)?.account

    /**
     * The resolved account, for states that cannot be reached without one (step-up, method change,
     * deletion). There a missing account is a broken state machine, so this crashes.
     */
    fun requireAccount(): AccountProfile =
        checkNotNull(account) { "Journey asked for an account before one was resolved" }

    /** The ACR this channel's evidence resolves to right now, e.g. a sub-journey's `startingAcr`. */
    val currentAcr: AcrLevel get() = policy.resolveAcr(evidence, account)

    /**
     * Has this session already proven [acr] (level and factors) for the account in hand? Without an
     * account, as for a process access, only the evidence counts.
     */
    infix fun hasReached(acr: AcrLevel): Boolean = policy.isSatisfied(evidence, acr, account)

    /** Could the account in hand reach [acr] in a future login with the methods it has now? */
    infix fun canReach(acr: AcrLevel): Boolean = policy.reachability(requireAccount(), acr) is Reachability.Reachable
}

/** Who a channel is signing in. One of three, never two at once. */
sealed interface SigningIn {
    /** Nobody yet: before any identification or lookup. */
    data object Unknown : SigningIn

    /** The holder of an account. */
    data class Holder(val account: AccountProfile) : SigningIn

    /** A process access with its one-time password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). */
    data class ProcessAccess(val invitation: InvitationId) : SigningIn
}
