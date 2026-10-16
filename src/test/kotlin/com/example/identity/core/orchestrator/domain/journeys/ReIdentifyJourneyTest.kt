package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.policy.fromNow
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.journeys.ReIdentifyJourney
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.method
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit coverage of [ReIdentifyJourney], which `FAST_ACCESS`, `LOOKUP_LOGIN` and `STEP_UP` fall into
 * once no active method reaches their target (docs/04-orchestrierung.md, "RE_IDENTIFY").
 */
class ReIdentifyJourneyTest : BehaviorSpec({

    val journey = ReIdentifyJourney

    given("Identifying, one offered candidate") {
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, Offer(listOf(ToolId("ident-fsc"))), ReIdentifyState.Kind.RECOVERY)

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
        val state = ReIdentifyState.OfferReIdent(AcrLevel.LOA2, ReIdentifyState.Kind.RECOVERY)

        `when`("accepted") {
            val transition = journey.transition(state, JourneyEvent.Answered(Answer.ACCEPT), theCtx)
            then("advances to Identifying, offering exactly the reachable IDENT tool(s)") {
                transition.shouldBeInstanceOf<Transition.To>()
                val to = transition.state
                to.shouldBeInstanceOf<ReIdentifyState.Identifying>()
                to.targetAcr shouldBe AcrLevel.LOA2
                to.offered shouldContainExactly listOf(ToolId("ident-fsc"))
            }
        }

        `when`("declined") {
            val transition = journey.transition(state, JourneyEvent.Answered(Answer.DECLINE), theCtx)
            then("cancels") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("(re-)started without an answer yet") {
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)
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
        val state = ReIdentifyState.OfferReIdent(AcrLevel.LOA2, ReIdentifyState.Kind.RECOVERY)

        `when`("accepted") {
            val transition = journey.transition(state, JourneyEvent.Answered(Answer.ACCEPT), theCtx)
            then("still cancels rather than erroring") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Identifying, more than one candidate offered") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"))), ReIdentifyState.Kind.RECOVERY)

        `when`("resumed after recording a proof that reaches the target (ActionCompleted)") {
            val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc))
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("resumed after recording a proof below the target (ActionCompleted)") {
            // Nect reports its level per procedure: a passport video ident proves less than offered for.
            val theCtx = ctx(
                account = acc,
                evidence = SessionEvidence.fromNow(
                    listOf("nect-epass"), setOf(FactorType.POSSESSION),
                    methodAcr = mapOf("nect-epass" to "loa1"), amrSourceId = mapOf("nect-epass" to "ident-nect")
                ),
                acrFloor = AcrLevel.LOA2
            )
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers the identifications still left instead of claiming success") {
                transition.shouldBeInstanceOf<Transition.To>()
                transition.state.shouldBeInstanceOf<ReIdentifyState.Identifying>().offered shouldContainExactly listOf(ToolId("ident-fsc"), ToolId("ident-eid"))
            }
        }
    }

    given("Identifying, the other candidate already declined") {
        val theCtx = ctx(account = account(method("sms", AcrLevel.LOA2)))
        val offer = Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid")), declined = setOf(ToolId("ident-eid")))
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, offer, ReIdentifyState.Kind.RECOVERY)

        `when`("the last remaining candidate is abandoned too") {
            val exhausted = state.declining(ToolId("ident-fsc"))
            val transition = journey.transition(exhausted as ReIdentifyState, JourneyEvent.OfferExhausted(exhausted.declined), theCtx)
            then("cancels - giving up here is not an error") {
                transition shouldBe Transition.Cancel
            }
        }
    }
})
