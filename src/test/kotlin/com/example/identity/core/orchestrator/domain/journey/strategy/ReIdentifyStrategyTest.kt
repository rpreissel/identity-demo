package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.policy.fromNow
import com.example.identity.core.orchestrator.domain.journey.strategy.ReIdentifyStrategy
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit coverage of [ReIdentifyStrategy], which `FAST_ACCESS`, `LOOKUP_LOGIN` and `STEP_UP` fall into
 * once no active method reaches their target (docs/04-orchestrierung.md, "RE_IDENTIFY").
 */
class ReIdentifyStrategyTest : BehaviorSpec({

    val strategy = ReIdentifyStrategy()

    given("the intent") {
        then("is RE_IDENTIFY") {
            strategy.intent shouldBe AuthIntent.RE_IDENTIFY
        }
    }

    given("ReIdentifyState.forSubJourney") {
        then("seeds OfferReIdent with exactly the given target/starting acr") {
            ReIdentifyState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1) shouldBe ReIdentifyState.OfferReIdent(AcrLevel.LOA2, AcrLevel.LOA1)
        }
    }

    given("Identifying, one offered candidate") {
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("ident-fsc"))))

        `when`("the tool completes with Identified") {
            val outcome = ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))
            val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("always confirms the caller's already-known account, never adopts a different one") {
                transition shouldBe
                    Transition.Perform(Action.RecordIdentification(IdentFscDescriptor, outcome), resumeState = state)
            }
        }

        `when`("the tool completes with Authenticated") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("fsc"))), ctx()) }
            then("Authenticated is not offered by this intent") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("the tool completes with Enrolled") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("fsc", "ref"))), ctx()) }
            then("Enrolled is not offered by this intent") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("OfferReIdent, with an IDENT tool that could still reach the target") {
        // eid and nect are marked used too, so fsc is the only identification left on offer.
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(
            account = acc,
            evidence = AuthEvidence.fromNow(
                listOf("sms", "eid", "nect-epass"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                amrSourceId = mapOf("nect-epass" to "ident-nect")
            ),
            acrFloor = AcrLevel.LOA2
        )
        val state = ReIdentifyState.OfferReIdent(AcrLevel.LOA2, AcrLevel.LOA1)

        `when`("accepted") {
            val transition = strategy.transition(state, JourneyEvent.Answered("accept"), theCtx)
            then("advances to Identifying, offering exactly the reachable IDENT tool(s)") {
                transition.shouldBeInstanceOf<Transition.To>()
                val to = transition.state
                to.shouldBeInstanceOf<ReIdentifyState.Identifying>()
                to.targetAcr shouldBe AcrLevel.LOA2
                to.startingAcr shouldBe AcrLevel.LOA1
                to.offered shouldContainExactly listOf(ToolId("ident-fsc"))
            }
        }

        `when`("declined") {
            val transition = strategy.transition(state, JourneyEvent.Answered("decline"), theCtx)
            then("cancels") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("an unrecognized answer is given") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Answered("maybe"), theCtx) }
            then("fails loudly rather than guessing") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("(re-)started without an answer yet") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            then("re-presents the same prompt, unconditionally") {
                transition shouldBe Transition.To(state)
            }
        }
    }

    given("OfferReIdent, no IDENT tool can close the gap (fsc, eid and nect already used this session)") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(
            account = acc,
            // Nect's amr is the procedure (`nect-eid`), not its method name. It counts as used
            // because ident-nect produced it.
            evidence = AuthEvidence.fromNow(
                listOf("sms", "fsc", "eid", "nect-eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                amrSourceId = mapOf("nect-eid" to "ident-nect")
            ),
            acrFloor = AcrLevel.LOA2
        )
        val state = ReIdentifyState.OfferReIdent(AcrLevel.LOA2, AcrLevel.LOA1)

        `when`("accepted") {
            val transition = strategy.transition(state, JourneyEvent.Answered("accept"), theCtx)
            then("still cancels rather than erroring") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Identifying, more than one candidate offered") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc)
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"))))
        val exhausted = state.withOffer(state.offer.copy(declined = setOf(ToolId("ident-eid"))))

        `when`("one is abandoned but another remains") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(IdentFscDescriptor), theCtx)
            then("advances, marking only that one declined") {
                transition shouldBe
                    Transition.To(state.declining(ToolId("ident-fsc")))
            }
        }

        `when`("the last remaining candidate is abandoned too") {
            val transition = strategy.transition(exhausted, JourneyEvent.Abandoned(IdentFscDescriptor), theCtx)
            then("cancels - giving up here is not an error") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("a proof completes (Completed)") {
            val outcome = ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))
            val completed = JourneyEvent.Completed(IdentFscDescriptor, outcome)
            val transition = strategy.transition(state, completed, theCtx)
            then("records the identification") {
                transition shouldBe
                    Transition.Perform(Action.RecordIdentification(IdentFscDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after recording the proof (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes directly - the identification's own maxAcr already IS the achieved level") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})
