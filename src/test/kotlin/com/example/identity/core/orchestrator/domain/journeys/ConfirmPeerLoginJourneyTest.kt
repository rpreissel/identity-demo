package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.state.ProofPurpose
import java.time.Duration
import com.example.identity.core.orchestrator.domain.policy.provenAt
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.domain.journeys.ConfirmPeerLoginJourney
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.method
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ids.AccountId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit test of [ConfirmPeerLoginJourney]: approving or declining a Web-channel QR pairing
 * (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN). Level and freshness are the FRESH_PROOF
 * sub-journey's ([FreshProofJourneyTest]); here only that it is required, and what follows it.
 */
class ConfirmPeerLoginJourneyTest : BehaviorSpec({

    val journey = ConfirmPeerLoginJourney

    // loa2 plus a recent proof, with no way out through re-identification, resuming at the parked wish.
    val peerLoginProof = Transition.RequireSubJourney(
        FreshProofJourney.before(ProofPurpose.PEER_LOGIN, AcrLevel.LOA2),
        resumeWith = ConfirmPeerLoginState.Requested
    )

    given("a new journey") {
        `when`("its first state is chosen") {
            val initial = journey.firstState

            then("it is Requested, for a cold entry and an authenticated channel alike") {
                initial shouldBe ConfirmPeerLoginState.Requested
            }
        }
    }

    given("Requested, a cold entry with no account at all") {
        `when`("the journey starts") {
            val transition = journey.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, ctx(account = null))
            then("aborts right here - never falls into identification/registration") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    given("Requested, the session carries loa1") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the journey starts") {
            val transition = journey.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, theCtx)
            then("parks the wish and requires loa2 plus a fresh proof, without re-identification as a way out") {
                transition shouldBe peerLoginProof
                ProofPurpose.PEER_LOGIN.allowsReIdentification shouldBe false
            }
        }
    }

    // Unlike DELETE_ACCOUNT and MANAGE_AUTH_METHODS (selfServiceAcrFloor), vouching for a foreign
    // login keeps loa2 for a never-identified account too (docs/journeys/confirm-peer-login.md).
    given("Requested, the account was never identified (personId == null) and the session only carries loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the journey starts") {
            val transition = journey.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, theCtx)
            then("the required level is not lowered - still loa2") {
                transition shouldBe peerLoginProof
            }
        }
    }

    given("Requested, the session already carries loa2 with a recent proof") {
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = JourneyTestFixtures.BINDING_KEY))
        val proven = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc)
        val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(4))), acrFloor = AcrLevel.LOA1)
        `when`("the journey starts") {
            val transition = journey.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, theCtx)
            then("still goes through the proof - whether the level and the age suffice is the sub-journey's call, never decided here") {
                transition.shouldBeInstanceOf<Transition.RequireSubJourney>().journey shouldBe FreshProofJourney
            }
        }
    }

    given("Requested, the approving tool switched off") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, availableTools = JourneyTestFixtures.allToolIds - ToolId("approve-qr"))
        `when`("the journey starts") {
            val transition = journey.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, theCtx)
            then("aborts before asking for a proof it could not use") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    // I-34: the proof is what counts, not the device link. A channel signed in on a device linked
    // to another account (a lookup login that declined the rebind) is gated like any other.
    given("Requested, an authenticated channel on a device linked to another account") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, linkedAccountId = AccountId(acc.accountId.value + 1))
        `when`("the journey starts") {
            val transition = journey.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, theCtx)
            then("requires loa2 and a fresh proof for the channel's own account") {
                transition shouldBe peerLoginProof
            }
        }
    }

    given("Requested, waiting for its proof") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val event = JourneyEvent.SubJourneyFinished(FreshProofJourney, achievedAcr = AcrLevel.LOA2)

        `when`("the FRESH_PROOF sub-journey finishes") {
            val transition = journey.transition(ConfirmPeerLoginState.Requested, event, ctx(account = acc))
            then("goes on to Confirming") {
                transition shouldBe Transition.To(ConfirmPeerLoginState.Confirming(CONFIRM_OFFER))
            }
        }
    }

    given("Confirming, account known") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc)
        val state = ConfirmPeerLoginState.Confirming(CONFIRM_OFFER)

        `when`("the approval has been recorded (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)

            then("the journey ends with the channel signed in - no logout question of its own (ADR-60)") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})

/** What the catalog offers for peer approval: approve-qr, derived from its role. */
private val CONFIRM_OFFER = Offer(listOf(ToolId("approve-qr")))
