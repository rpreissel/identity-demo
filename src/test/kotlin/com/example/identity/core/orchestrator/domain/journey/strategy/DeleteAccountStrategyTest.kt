package com.example.identity.core.orchestrator.domain.journey.strategy

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
 * The givens follow the journey: confirmation, loa2 gate, freshness, re-confirmation, delete.
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

    given("ConfirmPending, the session does not yet carry loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        `when`("the confirmation is accepted") {
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)
            then("the loa2 gate parks the wish and demands a step-up first - only NOW, never before accepting") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.STEP_UP,
                        seedWith = StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                        resumeWith = DeleteAccountState.ConfirmPending
                    )
            }
        }
    }

    given("ConfirmPending, the account was never identified (personId == null) and the session only carries loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), personId = null)
        val proven = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc)

        `when`("the confirmation is accepted and the proof is older than the self-service limit") {
            val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(6))))
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)
            then("loa1 already satisfies the gate - straight to the re-confirmation step, no STEP_UP to loa2 demanded") {
                val to = transition.shouldBeInstanceOf<Transition.To>().state
                to.shouldBeInstanceOf<DeleteAccountState.ConfirmationRequired>()
                to.offered shouldContainExactlyInAnyOrder listOf(ToolId("auth-sms"))
            }
        }

        `when`("the confirmation is accepted and the proof is recent") {
            val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(4))))
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)
            then("deletes right away - the recent proof is the fresh one") {
                transition shouldBe Transition.Perform(Action.DeleteAccount, resumeState = DeleteAccountState.ConfirmPending)
            }
        }
    }

    given("ConfirmPending, the session already carries loa2") {
        // device is the only tool whose own maxAcr reaches loa2 alone (sms/password/email cap
        // at loa1) - so this is the only single-method way to seed "already at loa2" evidence.
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = StrategyTestFixtures.BINDING_KEY))
        val proven = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc)

        `when`("the confirmation is accepted and the proof is older than the self-service limit") {
            val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(6))), acrFloor = AcrLevel.LOA1)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)
            then("demands a fresh re-confirmation of any active factor - the level alone is not enough") {
                val to = transition.shouldBeInstanceOf<Transition.To>().state
                to.shouldBeInstanceOf<DeleteAccountState.ConfirmationRequired>()
                to.offered shouldContainExactlyInAnyOrder listOf(ToolId("auth-device"))
            }
        }

        `when`("the confirmation is accepted and the proof is of unknown age") {
            val ageless = SessionEvidence(proven.methods.map { it.copy(provenAt = null) })
            val neverIdentified = acc.copy(personId = null)
            val theCtx = ctx(account = neverIdentified, evidence = ageless, acrFloor = AcrLevel.LOA1)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)
            then("demands the re-confirmation - an undated proof is never fresh") {
                transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<DeleteAccountState.ConfirmationRequired>()
            }
        }

        `when`("the confirmation is accepted and the proof is recent") {
            val theCtx = ctx(account = acc, evidence = proven.provenAt(TEST_NOW.minus(Duration.ofMinutes(4))), acrFloor = AcrLevel.LOA1)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)
            then("deletes right away - no second proof within the limit") {
                transition shouldBe Transition.Perform(Action.DeleteAccount, resumeState = DeleteAccountState.ConfirmPending)
            }
        }
    }

    given("ConfirmPending, waiting for a sub-journey") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc)

        `when`("the gate's own STEP_UP finishes and reached loa2 (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = AcrLevel.LOA2)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx)
            then("deletes right away - that fresh proof already IS the re-confirmation, no second one demanded") {
                transition shouldBe
                    Transition.Perform(Action.DeleteAccount, resumeState = DeleteAccountState.ConfirmPending)
            }
        }

        `when`("a STEP_UP finishes that fell short of loa2") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = AcrLevel.LOA1)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx)
            then("does not delete and does not fall back to a lesser reconfirmation either - that would let a session stuck below loa2 delete via the very factor that couldn't reach it") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("a different sub-journey entirely finishes - never assumed to be the gate's own") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA3)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx)
            then("does not delete") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("the gate's own STEP_UP was declined instead (SubJourneyCancelled)") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP)
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx)
            then("does not delete - same as falling short, not a lesser fallback") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("ConfirmPending, the delete after the gate's step-up") {
        `when`("the delete just ran (ActionCompleted)") {
            val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.ActionCompleted, ctx())
            then("ends the channel for good") {
                transition shouldBe Transition.Logout
            }
        }
    }

    given("ConfirmationRequired, more than one offered candidate") {
        val state = DeleteAccountState.ConfirmationRequired(Offer(listOf(ToolId("auth-sms"), ToolId("auth-password"))))
        `when`("one candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("auth-sms")), ctx())
            then("keeps the choice among the rest") {
                transition shouldBe
                    Transition.To(state.declining(ToolId("auth-sms")))
            }
        }
    }

    given("ConfirmationRequired, a single offered candidate") {
        val state = DeleteAccountState.ConfirmationRequired(Offer(listOf(ToolId("auth-sms"))))

        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("auth-sms")), ctx())
            then("cancels - the account is never deleted just because every option was declined") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("the delete just ran (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, ctx())
            then("ends the channel for good") {
                transition shouldBe Transition.Logout
            }
        }
    }

    given("ConfirmationRequired, a single offered candidate, account known") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc)
        val state = DeleteAccountState.ConfirmationRequired(Offer(listOf(ToolId("auth-sms"))))

        `when`("any active factor is re-proven") {
            val event = JourneyEvent.Completed(tool("auth-sms"), ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
            val transition = strategy.transition(state, event, theCtx)
            then("goes straight to deleting - one proof, at any level, is always sufficient here, and is never itself recorded as MethodEvidence") {
                transition shouldBe Transition.Perform(Action.DeleteAccount, resumeState = state)
            }
        }
    }

    given("ConfirmationRequired, offering an identification tool") {
        val state = DeleteAccountState.ConfirmationRequired(Offer(listOf(ToolId("ident-fsc"))))

        `when`("an outcome this intent never offers arrives: Identified") {
            val event = JourneyEvent.Completed(tool("ident-fsc"), identifiedOutcome())
            val result = runCatching { strategy.transition(state, event, ctx()) }
            then("Identified fails loudly rather than silently deleting") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("an outcome this intent never offers arrives: Enrolled") {
            val event = JourneyEvent.Completed(tool("auth-sms"), ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref")))
            val result = runCatching { strategy.transition(state, event, ctx()) }
            then("Enrolled fails loudly rather than silently deleting") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
