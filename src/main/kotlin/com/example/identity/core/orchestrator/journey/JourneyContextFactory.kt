package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.OpenJourneyContext
import com.example.identity.core.orchestrator.domain.journey.SigningIn
import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.session.SessionEvidenceService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.toCoreEvidence
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.tool_api.claims.AcrLevel
import org.springframework.stereotype.Component
import com.example.identity.core.orchestrator.domain.FeatureFlagProvider
import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag

/**
 * The reading phase of a transition: gathers what a journey may look at (account, evidence,
 * device link, flags) into the read-only [JourneyContext]. [JourneyService] re-derives it after
 * every action, so a journey never sees a stale picture. One assembly point makes that mechanical.
 */
@Component
class JourneyContextFactory(
    private val accountService: AccountService,
    private val sessionEvidenceService: SessionEvidenceService,
    private val sessionManagementService: SessionManagementService,
    private val authPolicy: AuthPolicy,
    private val toolRegistry: ToolHandlerRegistry,
    private val routing: JourneyRouting,
    private val featureFlagProviders: List<FeatureFlagProvider>
) {
    fun contextFor(journey: AuthJourney, channel: ChannelSession): JourneyContext {
        val evidence = channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }
        return OpenJourneyContext(
            signingIn = signingInOf(channel),
            evidence = evidence?.toCoreEvidence() ?: SessionEvidence(emptyList()),
            acrFloor = channel.acrFloor,
            bindingKeyRef = channel.bindingKeyRef,
            linkedAccountId = channel.bindingKeyRef?.let { sessionManagementService.findLinkedAccountId(it) },
            policy = authPolicy,
            catalog = toolRegistry,
            availableTools = routing.availableToolsOf(channel),
            featureFlags = journey.featureFlags
        )
    }

    private fun signingInOf(channel: ChannelSession): SigningIn = when (val subject = channel.subject) {
        is Subject.Account -> accountService.findAccount(subject.id)?.let { SigningIn.Holder(it) } ?: SigningIn.Unknown
        is Subject.Invitation -> SigningIn.ProcessAccess(subject.id)
        null -> SigningIn.Unknown
    }

    /** The switches active right now; a journey takes them over once, when it starts. */
    fun activeFeatureFlags(): Set<JourneyFeatureFlag> = featureFlagProviders.flatMapTo(mutableSetOf()) { it.activeFlags() }

    /** Recomputed from the evidence every time, never stored. */
    fun currentAcrOf(channel: ChannelSession): AcrLevel {
        val evidence = channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) } ?: return AcrLevel.NONE
        val account = channel.accountId?.let { accountService.findAccount(it) }
        return authPolicy.resolveAcr(evidence.toCoreEvidence(), account)
    }
}
