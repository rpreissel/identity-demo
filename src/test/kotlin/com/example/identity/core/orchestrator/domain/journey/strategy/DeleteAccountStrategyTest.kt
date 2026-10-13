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
 * Pure unit coverage of [DeleteAccountStrategy] - self-service account deletion
 * (docs/04-orchestrierung.md #3, docs/05-api.md "Account löschen"). No Spring context, no HTTP.
 * The givens follow the journey: confirmation, the FRESH_PROOF sub-journey, delete. Level and
 * freshness themselves are [FreshProofStrategyTest]'s.
 */
class DeleteAccountStrategyTest : BehaviorSpec({

    val strategy = DeleteAccountStrategy()

    given("a new journey") {
        `when`("its first state is chosen") {
            val initial = strategy.initialState(ctx())

            then("it is ConfirmPending - the yes/no confirmation always comes first, unconditionally") {
                initial shouldBe DeleteAccountState.ConfirmPending
            }
        }
    }

    given("ConfirmPending") {
        `when`("the journey starts") {
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Started, ctx())
            then("unconditionally re-presents the confirmation prompt") {
                transition shouldBe Transition.To(DeleteAccountState.ConfirmPending)
            }
        }

        `when`("the confirmation is declined") {
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_DECLINE), ctx())
            then("cancels - no gate is ever evaluated before an explicit yes") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("an unrecognized answer arrives") {
            val result = runCatching { strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("maybe"), ctx()) }
            then("fails loudly rather than guessing") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("ConfirmPending, an identified account at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the confirmation is accepted") {
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)
            then("parks the wish and requires loa2 plus a fresh proof - only NOW, never before accepting, and never decided here (ADR-61)") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.FRESH_PROOF,
                        seedWith = FreshProofState.forSubJourney(AcrLevel.LOA2, FreshProofState.Purpose.DELETE_ACCOUNT),
                        resumeWith = DeleteAccountState.ConfirmPending
                    )
            }
        }
    }

    given("ConfirmPending, the account was never identified (personId == null)") {
        val acc = account(method("sms", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the confirmation is accepted") {
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)
            then("requires only loa1 - selfServiceAcrFloor, not a second definition") {
                transition.shouldBeInstanceOf<Transition.RequireSubJourney>().seedWith
                    .shouldBeInstanceOf<FreshProofState.Start>().requiredAcr shouldBe AcrLevel.LOA1
            }
        }
    }

    given("ConfirmPending, waiting for its proof") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc)

        `when`("the FRESH_PROOF sub-journey finishes") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.FRESH_PROOF, achievedAcr = AcrLevel.LOA2)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx)
            then("deletes right away - level and freshness were the sub-journey's to decide") {
                transition shouldBe Transition.Perform(Action.DeleteAccount, resumeState = DeleteAccountState.ConfirmPending)
            }
        }

        `when`("a different sub-journey entirely finishes - never assumed to be the proof") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = AcrLevel.LOA3)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx)
            then("does not delete") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("the proof was declined (SubJourneyCancelled)") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.FRESH_PROOF)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx)
            then("does not delete - and does not ask again forever") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("the delete just ran (ActionCompleted)") {
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.ActionCompleted, theCtx)
            then("ends the channel for good") {
                transition shouldBe Transition.Logout
            }
        }
    }
})
