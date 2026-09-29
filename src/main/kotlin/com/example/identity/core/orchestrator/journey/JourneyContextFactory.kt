package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.session.channelType
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.session.AuthEvidenceService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.toCoreEvidence
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.tool_api.claims.AcrLevel
import org.springframework.stereotype.Component
import com.example.identity.core.orchestrator.domain.FeatureFlagProvider

/**
 * The reading phase of a transition: gathers what a strategy may look at (account, evidence,
 * device link, flags) into the read-only [JourneyContext]. [JourneyService] re-derives it after
 * every action, so a strategy never sees a stale picture. One assembly point makes that mechanical.
 */
@Component
class JourneyContextFactory(
    private val accountService: AccountService,
    private val authEvidenceService: AuthEvidenceService,
    private val sessionManagementService: SessionManagementService,
    private val authPolicy: AuthPolicy,
    private val toolRegistry: ToolHandlerRegistry,
    private val routing: JourneyRouting,
    private val featureFlagProviders: List<FeatureFlagProvider>
) {
    fun contextFor(journey: AuthJourney, channel: ChannelSession): JourneyContext {
        val accountId = channel.accountId
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        return JourneyContext(
            channel = channel.channelType,
            account = accountId?.let { accountService.findAccount(it) },
            invitation = channel.invitation,
            evidence = evidence?.toCoreEvidence() ?: AuthEvidence(emptyList()),
            acrFloor = acrFloorOf(channel),
            bindingKeyRef = channel.bindingKeyRef,
            linkedAccountId = channel.bindingKeyRef?.let { sessionManagementService.findLinkedAccountId(it) },
            isSubJourney = journey.parentJourneyId != null,
            policy = authPolicy,
            catalog = toolRegistry,
            availableTools = routing.availableToolsOf(channel),
            featureFlags = featureFlagProviders.flatMapTo(mutableSetOf()) { it.activeFlags() }
        )
    }

    fun acrFloorOf(channel: ChannelSession): AcrLevel =
        channel.acrFloor?.let(AcrLevel::of) ?: AcrLevels.DEFAULT_REQUIRED_ACR

    /** Recomputed from the evidence every time, never stored. */
    fun currentAcrOf(channel: ChannelSession): AcrLevel {
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) } ?: return AcrLevel.NONE
        val account = channel.accountId?.let { accountService.findAccount(it) }
        return authPolicy.resolveAcr(evidence.toCoreEvidence(), account)
    }
}
