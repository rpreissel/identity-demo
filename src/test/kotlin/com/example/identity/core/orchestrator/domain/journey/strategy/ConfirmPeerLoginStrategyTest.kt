package com.example.identity.core.orchestrator.domain.journey.strategy

import java.time.Duration
import com.example.identity.core.orchestrator.domain.policy.provenAt
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.domain.journey.strategy.ConfirmPeerLoginStrategy
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.identifiedOutcome
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit test of [ConfirmPeerLoginStrategy]: approving or declining a Web-channel QR pairing
 * (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN). Mirrors [DeleteAccountStrategyTest], because
 * both intents require a fresh re-proof for loa2 evidence of unknown age, but not after a
 * just-finished step-up.
 */
class ConfirmPeerLoginStrategyTest : BehaviorSpec({

    val strategy = ConfirmPeerLoginStrategy()

    // The loa2 gate: a step-up without re-identification, resuming at the parked wish.
    val peerLoginStepUp = Transition.RequireSubJourney(
        AuthIntent.STEP_UP,
        seedWith = StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1, allowReIdentification = false, reason = StepUpState.Reason.PEER_LOGIN),
        resumeWith = ConfirmPeerLoginState.Requested(false)
    )

    given("a new journey") {
        `when`("its first state is chosen") {
            val initial = strategy.initialState(ctx())

            then("it is Requested(startedAuthenticated = false) - the cold-entry default") {
                initial shouldBe ConfirmPeerLoginState.Requested(startedAuthenticated = false)
            }
        }
    }

    given("Requested, a cold entry with no account at all") {
        `when`("the journey starts") {
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(false), JourneyEvent.Started, ctx(account = null))
            then("aborts right here - never falls into identification/registration") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    given("Requested, the session does not yet carry loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the journey starts") {
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(false), JourneyEvent.Started, theCtx)
            then("the loa2 gate parks the wish and demands a step-up first") {
                transition shouldBe peerLoginStepUp
            }
        }
    }

    // Unlike DELETE_ACCOUNT and MANAGE_AUTH_METHODS (selfServiceAcrFloor), vouching for a foreign
    // login keeps loa2 for a never-identified account too (docs/journeys/confirm-peer-login.md).
    given("Requested, the account was never identified (personId == null) and the session only carries loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the journey starts") {
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(false), JourneyEvent.Started, theCtx)
            then("the loa2 gate is not lowered - a step-up to loa2 is still demanded") {
                transition shouldBe peerLoginStepUp
            }
        }
    }

    given("Requested, the session already carries loa2") {
        // device is the only method that reaches loa2 alone, so it seeds loa2 evidence with one method.
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = StrategyTestFixtures.BINDING_KEY))
        val proven = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc)

        `when`("the journey starts and the proof is older than the self-service limit") {
            val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(6))), acrFloor = AcrLevel.LOA1)
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(true), JourneyEvent.Started, theCtx)
            then("demands one fresh re-proof of any active factor - the level alone is not enough to vouch for a foreign login") {
                transition.shouldBeInstanceOf<Transition.To>()
                val to = transition.state
                to.shouldBeInstanceOf<ConfirmPeerLoginState.ConfirmationRequired>()
                to.offered shouldContainExactlyInAnyOrder listOf(ToolId("auth-device"))
                to.startedAuthenticated shouldBe true
            }
        }

        `when`("the journey starts and the proof is recent") {
            val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(4))), acrFloor = AcrLevel.LOA1)
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(true), JourneyEvent.Started, theCtx)
            then("goes straight to the approval - the recent proof is the fresh one") {
                val to = transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<ConfirmPeerLoginState.Confirming>()
                to.startedAuthenticated shouldBe true
            }
        }
    }

    given("Requested, waiting for a sub-journey") {
        `when`("the gate's own STEP_UP finishes and reached loa2 (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = AcrLevel.LOA2)
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(false), event, ctx())
            then("goes straight to Confirming - that fresh proof already IS the re-confirmation, no second one demanded") {
                transition shouldBe
                    Transition.To(ConfirmPeerLoginState.Confirming(false, CONFIRM_OFFER))
            }
        }

        `when`("the gate's own step-up was declined instead (SubJourneyCancelled)") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP)
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(false), event, ctx())
            then("cancels the whole wish - not re-requested forever") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Requested, waiting for a sub-journey, the session only carries loa1") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("a STEP_UP finishes that fell short of loa2") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = AcrLevel.LOA1)
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(false), event, theCtx)
            then("re-evaluates from scratch instead of silently accepting it as sufficient") {
                transition shouldBe peerLoginStepUp
            }
        }

        `when`("a different sub-journey entirely finishes - never assumed to be the gate's own") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA3)
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(false), event, theCtx)
            then("re-evaluates from scratch") {
                transition shouldBe peerLoginStepUp
            }
        }
    }

    given("ConfirmationRequired, more than one offered candidate") {
        val state = ConfirmPeerLoginState.ConfirmationRequired(false, Offer(listOf(ToolId("auth-sms"), ToolId("auth-password"))))
        `when`("one candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("auth-sms")), ctx())
            then("keeps the choice among the rest") {
                transition shouldBe
                    Transition.To(state.declining(ToolId("auth-sms")))
            }
        }
    }

    given("ConfirmationRequired, a single offered candidate") {
        val state = ConfirmPeerLoginState.ConfirmationRequired(false, Offer(listOf(ToolId("auth-sms"))))
        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("auth-sms")), ctx())
            then("cancels - the peer login is never confirmed just because every re-proof option was declined") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("ConfirmationRequired, started authenticated") {
        val state = ConfirmPeerLoginState.ConfirmationRequired(true, Offer(listOf(ToolId("auth-sms"))))
        `when`("any active factor is re-proven") {
            val event = JourneyEvent.Completed(tool("auth-sms"), ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
            val transition = strategy.transition(state, event, ctx())
            then("moves on to Confirming - one proof, at any level, is always sufficient here") {
                transition shouldBe Transition.To(ConfirmPeerLoginState.Confirming(true, CONFIRM_OFFER))
            }
        }
    }

    given("ConfirmationRequired, offering an identification tool") {
        val state = ConfirmPeerLoginState.ConfirmationRequired(false, Offer(listOf(ToolId("ident-fsc"))))
        `when`("an outcome this state never offers arrives (Identified)") {
            val event = JourneyEvent.Completed(tool("ident-fsc"), identifiedOutcome())
            val result = runCatching { strategy.transition(state, event, ctx()) }
            then("fails loudly rather than silently confirming") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("Confirming, account known") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc)
        val state = ConfirmPeerLoginState.Confirming(false, CONFIRM_OFFER)
        `when`("the approval just ran (Completed)") {
            val outcome = ToolOutcome.Completed.Approved()
            val event = JourneyEvent.Completed(tool("approve-qr"), outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("performs RecordApproval") {
                transition shouldBe Transition.Perform(Action.RecordApproval(tool("approve-qr"), outcome), resumeState = state)
            }
        }
    }
})

/** What the catalog offers for peer approval: approve-qr, derived from its role. */
private val CONFIRM_OFFER = Offer(listOf(ToolId("approve-qr")))
