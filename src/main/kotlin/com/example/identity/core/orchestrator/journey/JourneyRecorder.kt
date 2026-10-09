package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.SignInLog
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.example.identity.core.orchestrator.domain.policy.evidenceAxis
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.session.SessionEvidenceService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Records what a completed step writes outside the state machine's tables: restored evidence,
 * per-tool evidence, session events and the account's change log. It never decides anything.
 */
@Component
class JourneyRecorder(
    private val sessionEvidenceService: SessionEvidenceService,
    private val accountService: AccountService,
    private val signInLog: SignInLog,
) {

    /**
     * Adds what an earlier flow run proved to a fresh channel's evidence. The transition that
     * performs it lists the methods in the journey trace.
     */
    fun restoreEvidence(channel: ChannelSession, methods: List<MethodEvidence>) {
        sessionEvidenceService.attachToChannel(channel, methods)
    }

    /**
     * The MethodEvidence bookkeeping every tool-outcome [Action] needs. Records what the outcome
     * proved at [effectiveAcr]; any cap (as for [Action.AcceptProof]) is the caller's.
     */
    fun recordToolCompletion(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: Tool,
        outcome: ToolOutcome.Completed,
        effectiveAcr: AcrLevel?
    ) {
        val sessionEvidenceId = checkNotNull(channel.sessionEvidenceId) { "SessionEvidence missing after ${tool.toolId}" }
        val accountId = channel.accountId
        // A role on neither axis proves nothing about this session and leaves no evidence,
        // whatever it reported. Decided centrally, so a tool cannot mint assurance its role denies.
        val axis = tool.evidenceAxis()
        val updates = if (axis == null) emptyList() else tool.amrOf(outcome).map { method ->
            MethodEvidence(
                method = MethodName(method),
            // This run's achieved or capped level, else the tool's declared ceiling.
                loa = effectiveAcr ?: tool.maxAcr,
            // The account's enrollment record for this method (docs/06-ablaeufe.md #1).
                enrolledUnderAcr = accountId?.let { accountService.findActiveMethod(it, method)?.enrolledUnderAcr }?.let(AcrLevel::parse),
                factorTypes = tool.factorsOf(outcome),
                amrSourceId = tool.toolId.value,
                axis = axis,
            )
        }
        if (updates.isNotEmpty()) sessionEvidenceService.applyEvidence(sessionEvidenceId, updates)
    }

    /**
     * The audit row for both acts of ADR-18: `ident-eid` proving who somebody is, and `ident-kvnr`
     * binding that person to the register, the moment the PERSON_ID anchor is created. The act is
     * written as [ToolRole] in `details`: a correlation row carries the level of the
     * identification it rests on and would otherwise read as its own. Rows of one run share a `journeyId`.
     */
    fun recordIdentification(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: Tool,
        outcome: ToolOutcome.Completed.Identified
    ) {
        // Passed on whole: the change log decides what to keep (ChangeLog.identified).
        accountService.addIdentification(
            checkNotNull(channel.accountId),
            tool.method,
            tool.levelOf(outcome).value,
            role = tool.role.name,
            report = outcome.auditDetails.orEmpty(),
            tool = channel.declaredVersionOf(tool.toolId.value),
        )
    }

    /**
     * The sign-in log's view of a finished journey (ADR-39): an entry journey is a sign-in, a
     * step-up a step-up, anything else records nothing. [acr] is the level the session holds now.
     */
    fun recordSignIn(journey: AuthJourney, channel: ChannelSession, acr: AcrLevel) {
        val intent = journey.intent ?: return
        val evidence = channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }
        val amr = evidence?.currentAmr.orEmpty()
        // The orchestrator tools behind it, in the version this client spoke (ADR-51).
        val tools = evidence?.methods.orEmpty()
            .mapNotNull { it.amrSourceId?.let(channel::declaredVersionOf) }
            .distinct()
        when (val subject = channel.subject ?: return) {
            is Subject.Account -> when {
                intent == AuthIntent.STEP_UP -> signInLog.steppedUp(subject.id, channel.channel?.name, acr.value, amr, tools)
                intent.isEntryIntent -> signInLog.signedIn(subject.id, channel.channel?.name, acr.value, amr, tools, intent.name)
            }
            // A process access has one proof and no step-up (ADR-48).
            is Subject.Invitation -> signInLog.invitationSignedIn(subject.id, channel.channel?.name, acr.value, amr, tools)
        }
    }

    /** A session the holder ended on purpose. An expiry is not recorded. */
    fun recordSignOut(channel: ChannelSession, endedBy: String) {
        when (val subject = channel.subject ?: return) {
            is Subject.Account -> signInLog.signedOut(subject.id, channel.channel?.name, endedBy)
            is Subject.Invitation -> signInLog.invitationSignedOut(subject.id, channel.channel?.name, endedBy)
        }
    }
}
