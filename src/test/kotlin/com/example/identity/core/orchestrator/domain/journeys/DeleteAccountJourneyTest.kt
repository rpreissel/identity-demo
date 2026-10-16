package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [DeleteAccountJourney] - self-service account deletion
 * (docs/04-orchestrierung.md #3, docs/05-api.md "Account löschen"). No Spring context, no HTTP.
 * The givens follow the journey: confirmation, the FRESH_PROOF sub-journey, delete. Level and
 * freshness themselves are [FreshProofJourneyTest]'s.
 */
class DeleteAccountJourneyTest : BehaviorSpec({

    val journey = DeleteAccountJourney

    given("a new journey") {
        `when`("its first state is chosen") {
            val initial = journey.initialState(ctx())

            then("it is ConfirmPending - the yes/no confirmation always comes first, unconditionally") {
                initial shouldBe DeleteAccountState.ConfirmPending
            }
        }
    }

    given("ConfirmPending") {
        `when`("the journey starts") {
            val transition = journey.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Started, ctx())
            then("unconditionally re-presents the confirmation prompt") {
                transition shouldBe Transition.To(DeleteAccountState.ConfirmPending)
            }
        }

        `when`("the confirmation is declined") {
            val transition = journey.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(Answer.DECLINE), ctx())
            then("cancels - no gate is ever evaluated before an explicit yes") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("ConfirmPending, an identified account at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the confirmation is accepted") {
            val transition = journey.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(Answer.ACCEPT), theCtx)
            then("parks the wish and requires loa2 plus a fresh proof - only NOW, never before accepting, and never decided here (ADR-61)") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        FreshProofJourney,
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
            val transition = journey.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(Answer.ACCEPT), theCtx)
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
            val event = JourneyEvent.SubJourneyFinished(FreshProofJourney, achievedAcr = AcrLevel.LOA2)
            val transition = journey.transition(DeleteAccountState.ConfirmPending, event, theCtx)
            then("deletes right away - level and freshness were the sub-journey's to decide") {
                transition shouldBe Transition.Perform(Action.DeleteAccount, resumeState = DeleteAccountState.ConfirmPending)
            }
        }

        `when`("the delete just ran (ActionCompleted)") {
            val transition = journey.transition(DeleteAccountState.ConfirmPending, JourneyEvent.ActionCompleted, theCtx)
            then("ends the channel for good") {
                transition shouldBe Transition.Logout
            }
        }
    }
})
