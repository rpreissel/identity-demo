package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.SignInLog
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceService
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.example.identity.core.orchestrator.domain.policy.evidenceAxis
import com.example.identity.core.orchestrator.domain.AmrSource
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.session.AuthEvidenceService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import com.example.identity.core.orchestrator.session.forLog

/**
 * Records what a completed step writes outside the state machine's tables: evidence syncs,
 * per-tool evidence, session events and the account's change log. It never decides anything.
 */
@Component
class JourneyRecorder(
    private val authEvidenceService: AuthEvidenceService,
    private val accountService: AccountService,
    private val signInLog: SignInLog,
    private val journeyTraceService: JourneyTraceService,
    private val journeyTraceDetails: JourneyTraceDetails,
    private val codec: JourneyStateCodec
) {

    /** Merges [source]'s complete current evidence set into the channel. */
    fun mergeEvidence(journey: AuthJourney, channel: ChannelSession, source: String, updates: List<MethodEvidence>) {
        // [source]'s set before the update. Callers resend their complete set on every call
        // (docs/05-api.md Abschnitt 3), so only a real change is logged.
        val before = channel.authEvidenceId
            ?.let { authEvidenceService.getAuthEvidence(it) }?.amrEvidence.orEmpty()
            .filter { it.source == source }
            .map { Triple(it.method, it.loa, it.amrSourceId) }
            .toSet()
        val after = updates.map { Triple(it.method.value, it.loa.value, it.amrSourceId) }.toSet()

        authEvidenceService.attachToChannel(channel, source, updates)
        if (before != after) {
            // advance() logs the EvidenceReported transition, but not what changed. Without this
            // entry the trace would stay silent on Keycloak-native factors. snake_case marks an
            // entry that is no transition.
            journeyTraceService.record(channel.forLog(), journey.forLog(), "native_evidence_synced",
                journeyState = codec.read(journey)::class.simpleName,
                detail = mapOf("source" to source, "methods" to journeyTraceDetails.methodEvidenceDetail(updates))
            )
        }
    }

    /**
     * The MethodEvidence bookkeeping every tool-outcome [Action] needs. Records what the outcome
     * proved at [effectiveAcr]; any cap (as for [Action.AcceptProof]) is the caller's.
     */
    fun recordToolCompletion(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed,
        effectiveAcr: AcrLevel?
    ) {
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "AuthEvidence missing after ${tool.toolId}" }
        val accountId = channel.accountId
        // A role on neither axis proves nothing about this session and leaves no evidence,
        // whatever it reported. Decided centrally, so a tool cannot mint assurance its role denies.
        val axis = tool.evidenceAxis()
        val updates = if (axis == null) emptyList() else outcome.amr.map { method ->
            MethodEvidence(
                method = MethodName(method),
            // This run's achieved or capped level, else the tool's declared ceiling.
                loa = effectiveAcr ?: tool.maxAcr,
            // The account's enrollment record for this method (docs/06-ablaeufe.md #1).
                enrolledUnderAcr = accountId?.let { accountService.findActiveMethod(it, method)?.enrolledUnderAcr }?.let(AcrLevel::of),
                factorTypes = outcome.factorTypes,
                source = AmrSource.ORCHESTRATOR,
                amrSourceId = tool.toolId.value,
                axis = axis,
            )
        }
        if (updates.isNotEmpty()) authEvidenceService.applyEvidence(authEvidenceId, updates)
    }

    /**
     * The audit row for both acts of ADR-18: `ident-eid` proving who somebody is, and `ident-kvnr`
     * binding that person to the register, the moment the PERSON_ID anchor is created. The act is
     * written as [MethodRole] in `details`: a correlation row carries the level of the
     * identification it rests on and would otherwise read as its own. Rows of one run share a `journeyId`.
     */
    fun recordIdentification(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed.Identified
    ) {
        // Passed on whole: the change log decides what to keep (ChangeLog.identified).
        accountService.addIdentification(
            checkNotNull(channel.accountId),
            tool.method,
            outcome.achievedAcr?.value,
            role = tool.role.name,
            report = outcome.auditDetails.orEmpty(),
        )
    }

    /**
     * The sign-in log's view of a finished journey (ADR-39): an entry journey is a sign-in, a
     * step-up a step-up, anything else records nothing. [acr] is the level the session holds now.
     */
    fun recordSignIn(journey: AuthJourney, channel: ChannelSession, acr: AcrLevel) {
        val accountId = channel.accountId ?: return
        val intent = journey.intent ?: return
        val amr = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }?.currentAmr.orEmpty()
        when {
            intent == AuthIntent.STEP_UP -> signInLog.steppedUp(accountId, channel.channel?.name, acr.value, amr)
            intent.isEntryIntent -> signInLog.signedIn(accountId, channel.channel?.name, acr.value, amr, intent.name)
        }
    }

    /** A session the holder ended on purpose. An expiry is not recorded. */
    fun recordSignOut(channel: ChannelSession, endedBy: String) {
        val accountId = channel.accountId ?: return
        signInLog.signedOut(accountId, channel.channel?.name, endedBy)
    }
}
