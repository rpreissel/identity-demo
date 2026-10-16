package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.state.ProofPurpose
import com.example.identity.core.orchestrator.domain.journeys.StepUpJourney
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.state.upTo
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.method
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [StepUpJourney] - raising an already-authenticated channel's level
 * (docs/04-orchestrierung.md, "STEP_UP"). No Spring context, no HTTP.
 */
class StepUpJourneyTest : BehaviorSpec({

    val journey = StepUpJourney

    given("AuthChoice, one offered candidate") {
        val state = StepUpState.AuthChoice(AcrLevel.LOA1 upTo AcrLevel.LOA2, Offer(listOf(ToolId("auth-sms"))))

    }

    given("Start, an active method that can still help reach the target") {
        // sms alone caps at loa1, but it's unused this run - offering it "helps MFA" even before
        // it alone reaches loa2 (DefaultAuthPolicy.authCandidates' helpsMfa branch).
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = SessionEvidence(emptyList()))
        val state = StepUpState.Start(AcrLevel.LOA1 upTo AcrLevel.LOA2)

        `when`("freshly entered (Started)") {
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)
            then("offers it via AuthChoice") {
                transition shouldBe Transition.To(StepUpState.AuthChoice(AcrLevel.LOA1 upTo AcrLevel.LOA2, Offer(listOf(ToolId("auth-sms")))))
            }
        }
    }

    given("Start, the only active method already used this run, but re-identification could still help") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = StepUpState.Start(AcrLevel.LOA1 upTo AcrLevel.LOA2)

        `when`("freshly entered (Started)") {
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)
            then("requires the shared RE_IDENTIFY sub-journey instead of aborting") {
                transition shouldBe Transition.RequireSubJourney(
                    ReIdentifyJourney.toReach(AcrLevel.LOA2),
                    resumeWith = StepUpState.Start(AcrLevel.LOA1 upTo AcrLevel.LOA2)
                )
            }
        }
    }

    given("Start of a peer-login gate, which forbids re-identification, the only active method already used this run") {
        // Same fixture as the "re-identification could still help" case above - RE_IDENTIFY WOULD
        // be offered if allowed, proving this is the flag suppressing it, not just an absence of
        // candidates (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN #1: never identification).
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = StepUpState.Start((AcrLevel.LOA1 upTo AcrLevel.LOA2).copy(purpose = ProofPurpose.PEER_LOGIN))

        `when`("freshly entered (Started)") {
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)
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
            availableTools = JourneyTestFixtures.allToolIds - setOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect"))
        )
        val state = StepUpState.Start(AcrLevel.LOA1 upTo AcrLevel.LOA2)

        `when`("freshly entered (Started)") {
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)
            then("aborts with a reason, never a silent auto-pick") {
                transition.shouldBeInstanceOf<Transition.Abort>()
                transition.reason.template shouldContain "nicht erreichbar"
            }
        }
    }

    given("Start, the fresh evidence already satisfies the target") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = StepUpState.Start(AcrLevel.NONE upTo AcrLevel.LOA1)

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(ReIdentifyJourney, achievedAcr = AcrLevel.LOA2)
            val transition = journey.transition(state, event, theCtx)
            then("finishes directly instead of offering auth again") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Start, the evidence does not yet satisfy the target") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = SessionEvidence(emptyList()))
        val state = StepUpState.Start(AcrLevel.LOA1 upTo AcrLevel.LOA2)

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(ReIdentifyJourney, achievedAcr = AcrLevel.NONE)
            val transition = journey.transition(state, event, theCtx)
            then("falls through to offering auth candidates, same as a fresh Start") {
                transition shouldBe Transition.To(StepUpState.AuthChoice(AcrLevel.LOA1 upTo AcrLevel.LOA2, Offer(listOf(ToolId("auth-sms")))))
            }
        }
    }

    given("AuthChoice, one offered candidate, re-identification could still help") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = StepUpState.AuthChoice(AcrLevel.LOA1 upTo AcrLevel.LOA2, Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("the last offered candidate is abandoned") {
            val exhausted = state.declining(ToolId("auth-sms"))
            val transition = journey.transition(exhausted as StepUpState, JourneyEvent.OfferExhausted(exhausted.declined), theCtx)
            then("requires the shared RE_IDENTIFY sub-journey") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        ReIdentifyJourney.toReach(AcrLevel.LOA2),
                        resumeWith = StepUpState.Start(AcrLevel.LOA1 upTo AcrLevel.LOA2)
                    )
            }
        }
    }

    given("AuthChoice of a run with a purpose, re-identification could still help") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = StepUpState.AuthChoice((AcrLevel.LOA1 upTo AcrLevel.LOA2).copy(purpose = ProofPurpose.MANAGE_METHODS), Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("the last offered candidate is abandoned") {
            val exhausted = state.declining(ToolId("auth-sms"))
            val transition = journey.transition(exhausted as StepUpState, JourneyEvent.OfferExhausted(exhausted.declined), theCtx)
            then("the run resumes with its whole goal - the purpose survives the detour") {
                transition.shouldBeInstanceOf<Transition.RequireSubJourney>().resumeWith shouldBe
                    StepUpState.Start((AcrLevel.LOA1 upTo AcrLevel.LOA2).copy(purpose = ProofPurpose.MANAGE_METHODS))
            }
        }
    }

    given("AuthChoice, one offered candidate, re-identification cannot help either") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = StepUpState.AuthChoice(AcrLevel.LOA1 upTo AcrLevel.LOA2, Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms", "fsc", "eid", "nect-eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc, amrSourceId = mapOf("nect-eid" to "ident-nect")))

        `when`("the last offered candidate is abandoned") {
            val exhausted = state.declining(ToolId("auth-sms"))
            val transition = journey.transition(exhausted as StepUpState, JourneyEvent.OfferExhausted(exhausted.declined), theCtx)
            then("cancels - giving up here is not an error") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("AuthChoice, one offered candidate, re-identification could help, but this run forbids it (a peer-login gate)") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = StepUpState.AuthChoice((AcrLevel.LOA1 upTo AcrLevel.LOA2).copy(purpose = ProofPurpose.PEER_LOGIN), Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("the last offered candidate is abandoned") {
            val exhausted = state.declining(ToolId("auth-sms"))
            val transition = journey.transition(exhausted as StepUpState, JourneyEvent.OfferExhausted(exhausted.declined), theCtx)
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
        val state = StepUpState.AuthChoice(AcrLevel.LOA1 upTo AcrLevel.LOA2, Offer(listOf(ToolId("auth-sms"), ToolId("auth-password"))))

        `when`("resumed after accepting the proof (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes once the combination satisfies the target") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})
