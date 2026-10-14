package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import java.time.Duration
import com.example.identity.core.orchestrator.domain.policy.provenAt
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.domain.journey.strategy.ConfirmPeerLoginStrategy
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit test of [ConfirmPeerLoginStrategy]: approving or declining a Web-channel QR pairing
 * (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN). Level and freshness are the FRESH_PROOF
 * sub-journey's ([FreshProofStrategyTest]); here only that it is required, and what follows it.
 */
class ConfirmPeerLoginStrategyTest : BehaviorSpec({

    val strategy = ConfirmPeerLoginStrategy()

    // loa2 plus a recent proof, with no way out through re-identification, resuming at the parked wish.
    val peerLoginProof = Transition.RequireSubJourney(
        AuthIntent.FRESH_PROOF,
        seedWith = FreshProofState.forSubJourney(AcrLevel.LOA2, FreshProofState.Purpose.PEER_LOGIN),
        resumeWith = ConfirmPeerLoginState.Requested
    )

    given("a new journey") {
        `when`("its first state is chosen") {
            val initial = strategy.initialState(ctx())

            then("it is Requested, for a cold entry and an authenticated channel alike") {
                initial shouldBe ConfirmPeerLoginState.Requested
            }
        }
    }

    given("Requested, a cold entry with no account at all") {
        `when`("the journey starts") {
            val transition = strategy.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, ctx(account = null))
            then("aborts right here - never falls into identification/registration") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    given("Requested, the session carries loa1") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the journey starts") {
            val transition = strategy.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, theCtx)
            then("parks the wish and requires loa2 plus a fresh proof, without re-identification as a way out") {
                transition shouldBe peerLoginProof
                FreshProofState.Purpose.PEER_LOGIN.allowReIdentification shouldBe false
                FreshProofState.Purpose.PEER_LOGIN.stepUpReason shouldBe StepUpState.Reason.PEER_LOGIN
            }
        }
    }

    // Unlike DELETE_ACCOUNT and MANAGE_AUTH_METHODS (selfServiceAcrFloor), vouching for a foreign
    // login keeps loa2 for a never-identified account too (docs/journeys/confirm-peer-login.md).
    given("Requested, the account was never identified (personId == null) and the session only carries loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the journey starts") {
            val transition = strategy.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, theCtx)
            then("the required level is not lowered - still loa2") {
                transition shouldBe peerLoginProof
            }
        }
    }

    given("Requested, the session already carries loa2 with a recent proof") {
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = StrategyTestFixtures.BINDING_KEY))
        val proven = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc)
        val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(4))), acrFloor = AcrLevel.LOA1)
        `when`("the journey starts") {
            val transition = strategy.transition(ConfirmPeerLoginState.Requested, JourneyEvent.Started, theCtx)
            then("still goes through the proof - whether the level and the age suffice is the sub-journey's call, never decided here") {
                transition.shouldBeInstanceOf<Transition.RequireSubJourney>().intent shouldBe AuthIntent.FRESH_PROOF
            }
        }
    }

    given("Requested, waiting for its proof") {
        `when`("the FRESH_PROOF sub-journey finishes") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.FRESH_PROOF, achievedAcr = AcrLevel.LOA2)
            val transition = strategy.transition(ConfirmPeerLoginState.Requested, event, ctx())
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
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)

            then("the journey ends with the channel signed in - no logout question of its own (ADR-60)") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})

/** What the catalog offers for peer approval: approve-qr, derived from its role. */
private val CONFIRM_OFFER = Offer(listOf(ToolId("approve-qr")))
