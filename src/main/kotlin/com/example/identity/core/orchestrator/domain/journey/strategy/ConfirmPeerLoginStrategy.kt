package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.declineTool
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * Approve or decline a Web-channel `auth-qr`/`auth-qr-lookup` pairing
 * (docs/journeys/confirm-peer-login.md). The session must prove loa2 before it may vouch for a login
 * elsewhere. As in [DeleteAccountStrategy], it also needs a recent proof: a step-up from this journey
 * or any proof within the self-service limit counts, older evidence needs one fresh re-proof. That
 * re-proof is not recorded as `MethodEvidence`.
 */
class ConfirmPeerLoginStrategy : IntentStrategy<ConfirmPeerLoginState> {

    override val intent = AuthIntent.CONFIRM_PEER_LOGIN

    override fun initialState(ctx: JourneyContext): ConfirmPeerLoginState =
        ConfirmPeerLoginState.Requested(startedAuthenticated = false)

    override fun transition(state: ConfirmPeerLoginState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ConfirmPeerLoginState.Requested -> when (event) {
                // A declined step-up ends the wish instead of re-requesting it forever.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                // The gate's step-up reached loa2: that is the fresh proof. Any other finish
                // re-evaluates from scratch like every other event.
                is JourneyEvent.SubJourneyFinished if event.intent == AuthIntent.STEP_UP && (event.achievedAcr ?: AcrLevel.NONE) >= REQUIRED_ACR ->
                    Transition.To(confirming(ctx, state.startedAuthenticated))
                // gate() is null only when loa2 was already there; that case needs a recent proof.
                else -> gate(ctx, state.startedAuthenticated)
                    ?: if (ctx.policy.hasFreshProof(ctx.evidence)) Transition.To(confirming(ctx, state.startedAuthenticated))
                    else offerReconfirmation(ctx, state.startedAuthenticated)
            }

            is ConfirmPeerLoginState.ConfirmationRequired -> when (event) {
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                // Any active factor suffices; the re-proof is not recorded (class doc).
                is JourneyEvent.Completed -> when (event.outcome) {
                    is ToolOutcome.Completed.Authenticated -> Transition.To(confirming(ctx, state.startedAuthenticated))
                    is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
                        event.notOffered("CONFIRM_PEER_LOGIN's ConfirmationRequired")
                }
                else -> error("ConfirmationRequired does not understand $event")
            }

            is ConfirmPeerLoginState.Confirming -> when (event) {
                // Backing out is not declining the wish; the only candidate comes back.
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // The approval ran. A channel that logged in only for it is asked about logging out.
                is JourneyEvent.ActionCompleted ->
                    if (state.startedAuthenticated) Transition.Authenticated else Transition.To(ConfirmPeerLoginState.OfferLogout)
                else -> error("Confirming does not understand $event")
            }

            is ConfirmPeerLoginState.OfferLogout -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ANSWER_ACCEPT -> Transition.Logout
                    ANSWER_DECLINE -> Transition.Authenticated
                    else -> event.notUnderstood("OfferLogout")
                }
                else -> error("OfferLogout only accepts JourneyEvent.Answered")
            }
        }

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Approved -> Action.RecordApproval(event.tool, outcome)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled,
        is ToolOutcome.Completed.Authenticated, is ToolOutcome.Completed.Attested ->
            event.notOffered("CONFIRM_PEER_LOGIN")
    }

    private fun confirming(ctx: JourneyContext, startedAuthenticated: Boolean) =
        ConfirmPeerLoginState.Confirming(startedAuthenticated, Offer(CandidateTools.forPeerApproval(ctx)))

    /**
     * Null once the session already carries loa2 and the caller may proceed. Without an account (a
     * cold entry on an unlinked device) it aborts; this intent offers no registration.
     */
    private fun gate(ctx: JourneyContext, startedAuthenticated: Boolean): Transition? {
        val account = ctx.account
            ?: return Transition.Abort(Text("Dieses Gerät ist noch keinem Konto zugeordnet - bitte zuerst regulär anmelden."))
        if (ctx.policy.isSatisfied(ctx.evidence, REQUIRED_ACR, account)) return null
        return Transition.RequireSubJourney(
            AuthIntent.STEP_UP,
            // Never RE_IDENTIFY: a peer approval must not let someone acquire a fresh identity
            // just to confirm someone else's login.
            seedWith = StepUpState.forSubJourney(REQUIRED_ACR, ctx.currentAcr, allowReIdentification = false, reason = StepUpState.Reason.PEER_LOGIN),
            resumeWith = ConfirmPeerLoginState.Requested(startedAuthenticated)
        )
    }

    /** As in [DeleteAccountStrategy]. */
    private fun offerReconfirmation(ctx: JourneyContext, startedAuthenticated: Boolean): Transition {
        val candidates = CandidateTools.forReconfirmation(ctx.requireAccount(), ctx)
        return if (candidates.isEmpty()) {
            Transition.Abort(Text("Kein aktiver Faktor zur erneuten Bestaetigung verfuegbar"))
        } else {
            Transition.To(ConfirmPeerLoginState.ConfirmationRequired(startedAuthenticated, Offer(candidates)))
        }
    }

    companion object {
        val REQUIRED_ACR = AcrLevel.LOA2
    }
}
