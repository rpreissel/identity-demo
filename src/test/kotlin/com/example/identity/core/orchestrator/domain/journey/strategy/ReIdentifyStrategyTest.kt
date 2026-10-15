package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.policy.fromNow
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.journey.strategy.ReIdentifyStrategy
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
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

    given("Identifying, one offered candidate") {
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, Offer(listOf(ToolId("ident-fsc"))))

    }

    given("OfferReIdent, with an IDENT tool that could still reach the target") {
        // eid and nect are marked used too, so fsc is the only identification left on offer.
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(
            account = acc,
            evidence = SessionEvidence.fromNow(
                listOf("sms", "eid", "nect-epass"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                amrSourceId = mapOf("nect-epass" to "ident-nect")
            ),
            acrFloor = AcrLevel.LOA2
        )
        val state = ReIdentifyState.OfferReIdent(AcrLevel.LOA2)

        `when`("accepted") {
            val transition = strategy.transition(state, JourneyEvent.Answered(Answer.ACCEPT), theCtx)
            then("advances to Identifying, offering exactly the reachable IDENT tool(s)") {
                transition.shouldBeInstanceOf<Transition.To>()
                val to = transition.state
                to.shouldBeInstanceOf<ReIdentifyState.Identifying>()
                to.targetAcr shouldBe AcrLevel.LOA2
                to.offered shouldContainExactly listOf(ToolId("ident-fsc"))
            }
        }

        `when`("declined") {
            val transition = strategy.transition(state, JourneyEvent.Answered(Answer.DECLINE), theCtx)
            then("cancels") {
                transition shouldBe Transition.Cancel
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
            evidence = SessionEvidence.fromNow(
                listOf("sms", "fsc", "eid", "nect-eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                amrSourceId = mapOf("nect-eid" to "ident-nect")
            ),
            acrFloor = AcrLevel.LOA2
        )
        val state = ReIdentifyState.OfferReIdent(AcrLevel.LOA2)

        `when`("accepted") {
            val transition = strategy.transition(state, JourneyEvent.Answered(Answer.ACCEPT), theCtx)
            then("still cancels rather than erroring") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Identifying, more than one candidate offered") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc)
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"))))

        `when`("resumed after recording the proof (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes directly - the identification's own maxAcr already IS the achieved level") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Identifying, the other candidate already declined") {
        val theCtx = ctx(account = account(method("sms", AcrLevel.LOA2)))
        val offer = Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid")), declined = setOf(ToolId("ident-eid")))
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, offer)

        `when`("the last remaining candidate is abandoned too") {
            val exhausted = state.declining(ToolId("ident-fsc"))
            val transition = strategy.transition(exhausted as ReIdentifyState, JourneyEvent.OfferExhausted(exhausted.declined), theCtx)
            then("cancels - giving up here is not an error") {
                transition shouldBe Transition.Cancel
            }
        }
    }
})
