package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.strategy.StepUpStrategy
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.tools.auth_password.AuthPasswordDescriptor
import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [StepUpStrategy] - raising an already-authenticated channel's level
 * (docs/04-orchestrierung.md, "STEP_UP"). No Spring context, no HTTP.
 */
class StepUpStrategyTest : BehaviorSpec({

    val strategy = StepUpStrategy()

    given("the intent") {
        then("is STEP_UP") {
            strategy.intent shouldBe AuthIntent.STEP_UP
        }
    }

    given("StepUpState.forSubJourney") {
        then("seeds Start with exactly the given target/starting acr") {
            StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1) shouldBe StepUpState.Start(AcrLevel.LOA2, AcrLevel.LOA1)
        }
    }

    given("AuthChoice, one offered candidate") {
        val state = StepUpState.AuthChoice(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("auth-sms"))))

        `when`("the tool completes with Authenticated") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("is accepted as proof, never adopting a different account, always binding the device") {
                transition shouldBe
                    Transition.Perform(Action.AcceptProof(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("the tool completes with Identified") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Completed(AuthSmsDescriptor, ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))), ctx()) }
            then("Identified is not offered by this intent") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("the tool completes with Enrolled") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Completed(AuthSmsDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))), ctx()) }
            then("Enrolled is not offered by this intent") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("Start, an active method that can still help reach the target") {
        // sms alone caps at loa1, but it's unused this run - offering it "helps MFA" even before
        // it alone reaches loa2 (DefaultAuthPolicy.authCandidates' helpsMfa branch).
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = SessionEvidence(emptyList()))
        val state = StepUpState.Start(AcrLevel.LOA2, AcrLevel.LOA1)

        `when`("freshly entered (Started)") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            then("offers it via AuthChoice") {
                transition shouldBe Transition.To(StepUpState.AuthChoice(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("auth-sms")))))
            }
        }
    }

    given("Start, the only active method already used this run, but re-identification could still help") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = StepUpState.Start(AcrLevel.LOA2, AcrLevel.LOA1)

        `when`("freshly entered (Started)") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            then("requires the shared RE_IDENTIFY sub-journey instead of aborting") {
                transition shouldBe Transition.RequireSubJourney(
                    AuthIntent.RE_IDENTIFY,
                    seedWith = ReIdentifyState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                    resumeWith = StepUpState.Start(AcrLevel.LOA2, AcrLevel.LOA1)
                )
            }
        }
    }

    given("Start with allowReIdentification = false, the only active method already used this run") {
        // Same fixture as the "re-identification could still help" case above - RE_IDENTIFY WOULD
        // be offered if allowed, proving this is the flag suppressing it, not just an absence of
        // candidates (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN #1: never identification).
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = StepUpState.Start(AcrLevel.LOA2, AcrLevel.LOA1, allowReIdentification = false)

        `when`("freshly entered (Started)") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            then("aborts instead of offering RE_IDENTIFY") {
                transition.shouldBeInstanceOf<Transition.Abort>()
                transition.reason.template shouldContain "nicht erreichbar"
            }
        }
    }

    given("Start, nothing at all can close the gap (active method used, IDENT tools backend-disabled)") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            availableTools = StrategyTestFixtures.allToolIds - setOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect"))
        )
        val state = StepUpState.Start(AcrLevel.LOA2, AcrLevel.LOA1)

        `when`("freshly entered (Started)") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            then("aborts with a reason, never a silent auto-pick") {
                transition.shouldBeInstanceOf<Transition.Abort>()
                transition.reason.template shouldContain "nicht erreichbar"
            }
        }
    }

    given("Start, the fresh evidence already satisfies the target") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = StepUpState.Start(AcrLevel.LOA1, AcrLevel.NONE)

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA2)
            val transition = strategy.transition(state, event, theCtx)
            then("finishes directly instead of offering auth again") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Start, the evidence does not yet satisfy the target") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = SessionEvidence(emptyList()))
        val state = StepUpState.Start(AcrLevel.LOA2, AcrLevel.LOA1)

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = null)
            val transition = strategy.transition(state, event, theCtx)
            then("falls through to offering auth candidates, same as a fresh Start") {
                transition shouldBe Transition.To(StepUpState.AuthChoice(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("auth-sms")))))
            }
        }

        `when`("the RE_IDENTIFY sub-journey was declined instead (SubJourneyCancelled)") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
            val transition = strategy.transition(state, event, theCtx)
            then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("AuthChoice with more than one offered candidate") {
        val acc = account(method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2))
        val theCtx = ctx(account = acc)
        val state = StepUpState.AuthChoice(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("auth-sms"), ToolId("auth-password"))))

        `when`("one is abandoned, another remains") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsDescriptor), theCtx)
            then("advances, marking only that one declined") {
                transition shouldBe
                    Transition.To(state.declining(ToolId("auth-sms")))
            }
        }
    }

    given("AuthChoice, one offered candidate, re-identification could still help") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = StepUpState.AuthChoice(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsDescriptor), theCtx)
            then("requires the shared RE_IDENTIFY sub-journey") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.RE_IDENTIFY,
                        seedWith = ReIdentifyState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                        resumeWith = StepUpState.Start(AcrLevel.LOA2, AcrLevel.LOA1)
                    )
            }
        }
    }

    given("AuthChoice, one offered candidate, re-identification cannot help either") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = StepUpState.AuthChoice(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms", "fsc", "eid", "nect-eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc, amrSourceId = mapOf("nect-eid" to "ident-nect")))

        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsDescriptor), theCtx)
            then("cancels - giving up here is not an error") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("AuthChoice, one offered candidate, re-identification could help, but this run forbids it (allowReIdentification = false)") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = StepUpState.AuthChoice(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("auth-sms"))))
        val forbiddenState = state.copy(allowReIdentification = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(forbiddenState, JourneyEvent.Abandoned(AuthSmsDescriptor), theCtx)
            then("cancels instead of requiring RE_IDENTIFY") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("AuthChoice, sms and password proven this run") {
        // sms+password, both loa1 alone, both enrolled under loa2 - MFA-combine to loa2 (see
        // DefaultAuthPolicyTest for the underlying combination rule).
        val acc = account(method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc))
        val state = StepUpState.AuthChoice(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("auth-sms"), ToolId("auth-password"))))

        `when`("a proof just completed (Completed)") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("password"))
            val event = JourneyEvent.Completed(AuthPasswordDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("performs AcceptProof") {
                transition shouldBe
                    Transition.Perform(Action.AcceptProof(AuthPasswordDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after accepting the proof (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes once the combination satisfies the target") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})
