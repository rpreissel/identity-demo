package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.declineTool
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.KcSelectMethodState
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * The Web entry intent (docs/journeys/kc-select-method.md): one `selectMethod` step listing every
 * kc-usable tool. Keycloak's flow decides whether the reached level is enough; this strategy only
 * answers "what could still prove something here". Without an account it offers lookup-login
 * tools and the one-time password of a process access (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md);
 * with one (a step-up, docs/05-api.md Abschnitt 3) auth tools for that account.
 */
class KcSelectMethodStrategy : IntentStrategy<KcSelectMethodState> {

    override val intent = AuthIntent.KC_SELECT_METHOD

    override fun initialState(ctx: JourneyContext): KcSelectMethodState =
        KcSelectMethodState.SelectMethod(Offer(candidatesFor(ctx)), accountAlreadyKnown = ctx.account != null)

    override fun transition(state: KcSelectMethodState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is KcSelectMethodState.SelectMethod -> when (event) {
                // Restored evidence may already satisfy the floor (docs/04-orchestrierung.md,
                // "RestoreData als erster Übergang"), so Started re-checks like any other proof.
                is JourneyEvent.Started -> afterProof(ctx)
                is JourneyEvent.Completed -> completed(state, event, ctx)
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                // EvidenceReported and ActionCompleted re-check the same way.
                else -> afterProof(ctx)
            }
        }

    /**
     * A one-time password below the level this login asks for is refused before anything is bound:
     * the invitation is what it is, and no other tool could raise a session that has no account.
     */
    private fun completed(state: KcSelectMethodState.SelectMethod, event: JourneyEvent.Completed, ctx: JourneyContext): Transition {
        val outcome = event.outcome
        if (outcome is ToolOutcome.Completed.Authenticated && outcome.subject is Subject.Invitation &&
            (outcome.achievedAcr ?: AcrLevel.NONE) < ctx.acrFloor
        ) return insufficientInvitation()
        return Transition.Perform(proofAction(state, event), resumeState = state)
    }

    private fun insufficientInvitation() =
        Transition.Abort(Text("Dieses Einmalkennwort genuegt der verlangten Sicherheitsstufe nicht"))

    /**
     * Which account the proof names is decided at execution time
     * ([com.example.identity.core.orchestrator.domain.journey.accountOfProof]). Other outcomes mean a tool ran
     * that was never offered.
     */
    private fun proofAction(state: KcSelectMethodState.SelectMethod, event: JourneyEvent.Completed): Action =
        when (val outcome = event.outcome) {
            is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome)
            is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
                error("${event.tool.toolId} is not offered by KC_SELECT_METHOD")
        }

    private fun afterProof(ctx: JourneyContext): Transition {
        // A process access is complete with its one proof; it has nothing else to offer.
        if (ctx.invitation != null) {
            return if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, null)) Transition.Authenticated else insufficientInvitation()
        }
        val account = ctx.account
        if (account != null && ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Transition.Authenticated
        return Transition.To(KcSelectMethodState.SelectMethod(Offer(candidatesFor(ctx)), accountAlreadyKnown = account != null))
    }

    /**
     * Never identification and never enrollment (docs/04-orchestrierung.md Abschnitt 3): the Web
     * channel only proves an existing identity. Fresh identification exists only in the app.
     */
    private fun candidatesFor(ctx: JourneyContext): List<ToolId> {
        val account = ctx.account
        return if (account == null) {
            CandidateTools.forLookupLogin(ctx)
        } else {
            CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        }
    }
}
