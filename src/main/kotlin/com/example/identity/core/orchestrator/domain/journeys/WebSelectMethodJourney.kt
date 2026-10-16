package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.core.orchestrator.domain.InvitationAcceptance
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.OpensWebChannel
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.WebSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.toAuthAbortMessage
import com.example.identity.contract.tool_api.ToolId

/**
 * The Web entry journey (docs/journeys/web-select-method.md): one `selectMethod` step listing every
 * Keycloak-usable tool, without fallback chain or enrollment; Keycloak only renders the tools' pages
 * (ADR-58). Keycloak's flow decides whether the reached level is enough; this journey only
 * answers "what could still prove something here". Without an account it offers lookup-login
 * tools and the one-time password of a process access (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md);
 * with one (a step-up, docs/05-api.md Abschnitt 3b) auth tools for that account. When none of them can
 * close the gap, the step-up falls back to `RE_IDENTIFY`, as STEP_UP does in the app.
 */
object WebSelectMethodJourney : JourneyWithoutInput<WebSelectMethodState> {

    override val name = "WEB_SELECT_METHOD"
    override val startPoints = listOf(OpensWebChannel)
    override val stateRoots = listOf(WebSelectMethodState::class)

    override fun initialState(ctx: JourneyContext): WebSelectMethodState = selectMethod(ctx)

    override fun transition(state: WebSelectMethodState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is WebSelectMethodState.SelectMethod -> when (event) {
                is JourneyEvent.OfferExhausted -> ctx.account?.let { reIdentifyOr(ctx.acrFloor, ctx, resumeWith = WebSelectMethodState.AfterIdentification) { Transition.Cancel } } ?: Transition.Cancel
                // Started re-checks like any other proof: restored evidence may already satisfy the
                // floor (docs/04-orchestrierung.md, "Übernommene Nachweise als erster Übergang"). So does
                // ActionCompleted.
                else -> afterProof(ctx)
            }

            // The identification may already close the gap; otherwise the offer is built anew.
            is WebSelectMethodState.AfterIdentification -> afterProof(ctx)
        }

    private fun afterProof(ctx: JourneyContext): Transition {
        // A process access is complete with its one proof; it has nothing else to offer. Its level
        // was already checked on acceptance (InvitationAcceptance); this catches what the level
        // alone does not say, such as a second factor the floor demands.
        if (ctx.invitation != null) {
            return if (ctx hasReached ctx.acrFloor) Transition.Authenticated
            else Transition.Abort(InvitationAcceptance.Refusal.BELOW_REQUIRED_LEVEL.text)
        }
        val account = ctx.account
        if (account != null && ctx hasReached ctx.acrFloor) return Transition.Authenticated
        val next = selectMethod(ctx)
        if (account == null || next.offer.offered.isNotEmpty()) return Transition.To(next)
        // No method of this account can close the gap here: identifying again can, as in STEP_UP.
        // Failing that, say why instead of handing Keycloak an empty selection.
        return reIdentifyOr(ctx.acrFloor, ctx, resumeWith = WebSelectMethodState.AfterIdentification) {
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toAuthAbortMessage())
        }
    }

    private fun selectMethod(ctx: JourneyContext) =
        WebSelectMethodState.SelectMethod(Offer(candidatesFor(ctx)), accountAlreadyKnown = ctx.account != null)

    /**
     * Never enrollment, and identification only as the step-up's way out (`RE_IDENTIFY`, see
     * [afterProof]), never as a first login: without an account there is nobody to identify.
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
