package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import java.time.Duration
import com.example.identity.core.orchestrator.domain.policy.provenAt
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.identifiedOutcome
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [FreshProofStrategy], the sub-journey every self-service action runs
 * first (docs/journeys/fresh-proof.md, ADR-61): the level gate, the freshness of the latest proof,
 * and the re-proof of one active factor.
 */
class FreshProofStrategyTest : BehaviorSpec({

    val strategy = FreshProofStrategy()
    fun start(requiredAcr: AcrLevel = AcrLevel.LOA2, purpose: FreshProofState.Purpose = FreshProofState.Purpose.DELETE_ACCOUNT) =
        FreshProofState.Start(requiredAcr, purpose)

    given("Start, the session does not yet carry the required loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("the sub-journey starts") {
            val transition = strategy.transition(start(), JourneyEvent.Started, theCtx)
            then("requires a step-up to that level, resuming here") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.STEP_UP,
                        seedWith = StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                        resumeWith = start()
                    )
            }
        }

        `when`("the sub-journey starts for a peer login") {
            val transition = strategy.transition(start(purpose = FreshProofState.Purpose.PEER_LOGIN), JourneyEvent.Started, theCtx)
            then("the step-up offers no re-identification as a way out and explains the peer login") {
                transition.shouldBeInstanceOf<Transition.RequireSubJourney>().seedWith shouldBe
                    StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1, allowReIdentification = false, reason = StepUpState.Reason.PEER_LOGIN)
            }
        }

        `when`("its STEP_UP finishes and reached the level (SubJourneyFinished)") {
            val transition = strategy.transition(start(), JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = AcrLevel.LOA2), theCtx)
            then("is done - that step-up already IS the fresh proof, no second one demanded") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("a STEP_UP finishes that fell short of the level") {
            val transition = strategy.transition(start(), JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = AcrLevel.LOA1), theCtx)
            then("cancels - a lesser re-proof would let a session stuck below the level pass via the very factor that couldn't reach it") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("a different sub-journey entirely finishes - never assumed to be the step-up") {
            val transition = strategy.transition(start(), JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA3), theCtx)
            then("cancels") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("the step-up was declined (SubJourneyCancelled)") {
            val transition = strategy.transition(start(), JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP), theCtx)
            then("cancels instead of re-requesting it forever") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Start, a level the account already satisfies") {
        // device is the only tool whose own maxAcr reaches loa2 alone (sms/password/email cap at loa1).
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = StrategyTestFixtures.BINDING_KEY))
        val proven = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc)

        `when`("the latest proof is recent") {
            val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(4))), acrFloor = AcrLevel.LOA1)
            val transition = strategy.transition(start(), JourneyEvent.Started, theCtx)
            then("is done at once - no second proof within the limit") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("the latest proof is older than the self-service limit") {
            val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(6))), acrFloor = AcrLevel.LOA1)
            val transition = strategy.transition(start(), JourneyEvent.Started, theCtx)
            then("demands a fresh re-proof of any active factor - the level alone is not enough") {
                val to = transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<FreshProofState.Reproving>()
                to.offered shouldContainExactlyInAnyOrder listOf(ToolId("auth-device"))
                to.purpose shouldBe FreshProofState.Purpose.DELETE_ACCOUNT
            }
        }

        `when`("the latest proof is of unknown age") {
            val ageless = SessionEvidence(proven.methods.map { it.copy(provenAt = null) })
            val theCtx = ctx(account = acc.copy(personId = null), evidence = ageless, acrFloor = AcrLevel.LOA1)
            val transition = strategy.transition(start(AcrLevel.LOA1), JourneyEvent.Started, theCtx)
            then("demands the re-proof - an undated proof is never fresh") {
                transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<FreshProofState.Reproving>()
            }
        }
    }

    given("Start, a session that satisfies the level but has no factor usable here") {
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = "another-device"))
        val proven = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc)
        val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(6))), acrFloor = AcrLevel.LOA1)

        `when`("the sub-journey starts") {
            val transition = strategy.transition(start(), JourneyEvent.Started, theCtx)
            then("aborts rather than passing silently") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    given("Reproving, more than one offered candidate") {
        val state = FreshProofState.Reproving(AcrLevel.LOA2, FreshProofState.Purpose.MANAGE_METHODS, Offer(listOf(ToolId("auth-sms"), ToolId("auth-password"))))

        `when`("one candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("auth-sms")), ctx())
            then("keeps the choice among the rest") {
                transition shouldBe Transition.To(state.declining(ToolId("auth-sms")))
            }
        }

        `when`("any active factor is re-proven") {
            val event = JourneyEvent.Completed(tool("auth-sms"), ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
            val transition = strategy.transition(state, event, ctx())
            then("is done - one proof, at any level, and never itself recorded as MethodEvidence (no Perform)") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Reproving, a single offered candidate") {
        val state = FreshProofState.Reproving(AcrLevel.LOA2, FreshProofState.Purpose.PEER_LOGIN, Offer(listOf(ToolId("auth-sms"))))

        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("auth-sms")), ctx())
            then("cancels - nothing passes just because every option was declined") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("an outcome this sub-journey never offers arrives: Identified") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Completed(tool("ident-fsc"), identifiedOutcome()), ctx()) }
            then("fails loudly rather than silently passing") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("an outcome this sub-journey never offers arrives: Enrolled") {
            val event = JourneyEvent.Completed(tool("auth-sms"), ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref")))
            val result = runCatching { strategy.transition(state, event, ctx()) }
            then("fails loudly") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("the wording each purpose brings") {
        val offer = Offer(listOf(ToolId("auth-sms")))
        then("every purpose has a title and a description of its own") {
            FreshProofState.Purpose.entries.map { FreshProofState.Reproving(AcrLevel.LOA2, it, offer).selectionTitle }.toSet().size shouldBe FreshProofState.Purpose.entries.size
        }
    }
})
