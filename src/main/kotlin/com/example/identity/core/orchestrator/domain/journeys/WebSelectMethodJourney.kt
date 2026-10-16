package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.authCandidates
import com.example.identity.core.orchestrator.domain.journey.offer
import com.example.identity.core.orchestrator.domain.journey.lookupLoginCandidates
import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.InvitationAcceptance
import com.example.identity.core.orchestrator.domain.journey.OpensWebChannel
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.WebSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.toAuthAbortMessage

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
    override val stateRoot = WebSelectMethodState::class

    override val firstState = WebSelectMethodState.Start

    override fun transition(state: WebSelectMethodState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is WebSelectMethodState.SelectMethod -> when (event) {
                // Nothing left on offer: a step-up may still identify again, a first login just ends.
                is JourneyEvent.OfferExhausted ->
                    if (ctx.account == null) Transition.Cancel
                    else reIdentifyOr(ctx) { Transition.Cancel }
                else -> finishOrOfferMore(ctx)
            }

            // Started, or back from RE_IDENTIFY: taken-over evidence (docs/04-orchestrierung.md,
            // "Übernommene Nachweise als erster Übergang") or the identification may already close
            // the gap; otherwise the offer is built anew.
            is WebSelectMethodState.Start -> finishOrOfferMore(ctx)
        }

    /** Done once the channel's floor is proven; otherwise what can still prove more depends on who is signing in. */
    private fun finishOrOfferMore(ctx: JourneyContext): Transition {
        if (ctx hasReached ctx.acrFloor) return Transition.Authenticated
        val account = ctx.account
        return when {
            // A process access has its one proof and nothing else to offer. Its level was checked on
            // acceptance (InvitationAcceptance); this catches what the level alone does not say,
            // such as a second factor the floor demands.
            ctx.invitation != null -> Transition.Abort(InvitationAcceptance.Refusal.BELOW_REQUIRED_LEVEL.text)
            account == null -> offerLookupLogin(ctx)
            else -> offerStepUp(account, ctx)
        }
    }

    /** Without an account nobody can be identified: only lookup-login tools, never enrollment. */
    private fun offerLookupLogin(ctx: JourneyContext): Transition =
        Transition.To(WebSelectMethodState.SelectMethod(Offer(ctx.lookupLoginCandidates()), accountAlreadyKnown = false))

    /**
     * The account's methods. When none of them can close the gap here, identifying again can, as in
     * STEP_UP; failing that, say why instead of handing Keycloak an empty selection.
     */
    private fun offerStepUp(account: AccountProfile, ctx: JourneyContext): Transition =
        offer(ctx.authCandidates()) { WebSelectMethodState.SelectMethod(it, accountAlreadyKnown = true) }
            ?: reIdentifyOr(ctx) { Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toAuthAbortMessage()) }

    /** RE_IDENTIFY up to the channel's floor, then back to [WebSelectMethodState.Start]; [otherwise] when it cannot help. */
    private fun reIdentifyOr(ctx: JourneyContext, otherwise: () -> Transition): Transition =
        ReIdentifyJourney.toReachOr(ctx.acrFloor, ctx, resumeWith = WebSelectMethodState.Start) { otherwise() }
}
