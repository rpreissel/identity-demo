package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.tool
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.WebSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.webTools
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit test of [WebSelectMethodJourney] (docs/journeys/web-select-method.md) on the Web channel:
 * lookup-login tools without an account, auth tools for a known one, a finished journey once the
 * evidence meets the floor, never enrollment, and identification only as the step-up's way out.
 */
class WebSelectMethodJourneyTest : BehaviorSpec({

    val journey = WebSelectMethodJourney
    val lookupTools = listOf("auth-sms-lookup", "auth-password-lookup", "auth-qr-lookup", "auth-invite-lookup").map(::ToolId)

    fun webCtx(
        account: AccountProfile? = null,
        evidence: SessionEvidence = SessionEvidence(emptyList()),
        acrFloor: AcrLevel = AcrLevel.LOA1
    ) = ctx(account = account, evidence = evidence, acrFloor = acrFloor, availableTools = webTools, channel = ChannelType.WEB)

    given("no account known yet") {
        `when`("a new journey is created") {
            val initial = journey.initialState(webCtx())

            then("it offers the Web channel's lookup-login tools") {
                initial.shouldBeInstanceOf<WebSelectMethodState.SelectMethod>()
                initial.offer.offered shouldContainExactlyInAnyOrder lookupTools
                initial.accountAlreadyKnown shouldBe false
            }
        }
    }

    given("a known account with sms and password, sms proven, the floor at loa2 (a Web step-up)") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1))
        val theCtx = webCtx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)

        `when`("a new journey is created") {
            val initial = journey.initialState(theCtx)

            then("it offers only the account's auth tool still missing, no lookup tool") {
                initial shouldBe WebSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-password"))), accountAlreadyKnown = true)
            }
        }
    }

    given("SelectMethod without an account") {
        val state = WebSelectMethodState.SelectMethod(Offer(lookupTools), accountAlreadyKnown = false)

        `when`("the journey starts") {
            val transition = journey.transition(state, JourneyEvent.Started, webCtx())

            then("it keeps offering the lookup-login tools") {
                transition shouldBe Transition.To(WebSelectMethodState.SelectMethod(Offer(state.offer.offered), accountAlreadyKnown = false))
            }
        }

    }

    given("SelectMethod with a single offered tool") {
        val state = WebSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-sms-lookup"))), accountAlreadyKnown = false)

        `when`("that tool is abandoned") {
            val exhausted = state.declining(ToolId("auth-sms-lookup"))
            val transition = journey.transition(exhausted as WebSelectMethodState, JourneyEvent.OfferExhausted(exhausted.declined), webCtx())
            then("it cancels") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("SelectMethod, the account known and the evidence meeting the floor") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = webCtx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = WebSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-sms"))), accountAlreadyKnown = true)

        `when`("the journey starts, e.g. with restored evidence") {
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)

            then("it is authenticated at once") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("the accepted proof has been booked (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)

            then("it is authenticated") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("SelectMethod, the account known but the evidence below the floor") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1))
        val theCtx = webCtx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)
        val state = WebSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-password"))), accountAlreadyKnown = true)

        `when`("an action brought evidence that still falls short") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)

            then("it offers the account's auth tools again") {
                transition shouldBe
                    Transition.To(WebSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-password"))), accountAlreadyKnown = true))
            }
        }
    }

    // A step-up where no method of the account can close the gap on the Web: sms is already proven,
    // the device works only in the app. As STEP_UP does in the app, identifying again is the way out.
    val smsAndDevice = account(method("sms", AcrLevel.LOA2), method("device", AcrLevel.LOA2, boundKeyRef = "app-key"))
    val smsProven = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = smsAndDevice)

    given("a Web step-up to loa2 whose account has no further method usable here") {
        val theCtx = webCtx(account = smsAndDevice, evidence = smsProven, acrFloor = AcrLevel.LOA2)
        val state = journey.initialState(theCtx)

        `when`("the journey starts") {
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)

            then("it requires RE_IDENTIFY and resumes where the offer is built anew") {
                transition shouldBe Transition.RequireSubJourney(
                    ReIdentifyJourney,
                    seedWith = ReIdentifyState.forSubJourney(AcrLevel.LOA2),
                    resumeWith = WebSelectMethodState.AfterIdentification
                )
            }
        }
    }

    given("the same step-up, but no identification tool is available on the Web") {
        val theCtx = ctx(
            account = smsAndDevice, evidence = smsProven, acrFloor = AcrLevel.LOA2, channel = ChannelType.WEB,
            availableTools = webTools - setOf(ToolId("ident-fsc"), ToolId("ident-eid"))
        )

        `when`("the journey starts") {
            val transition = journey.transition(journey.initialState(theCtx), JourneyEvent.Started, theCtx)

            then("it aborts and says why, instead of handing Keycloak an empty selection") {
                transition.shouldBeInstanceOf<Transition.Abort>()
                transition.reason.template shouldContain "kein passendes Anmeldeverfahren"
            }
        }
    }

    given("a Web step-up offering one auth tool") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1))
        val theCtx = webCtx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)
        val state = WebSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-password"))), accountAlreadyKnown = true)

        `when`("that last tool is abandoned") {
            val exhausted = state.declining(ToolId("auth-password"))
            val transition = journey.transition(exhausted as WebSelectMethodState, JourneyEvent.OfferExhausted(exhausted.declined), theCtx)
            then("it offers identifying again instead of giving up") {
                transition.shouldBeInstanceOf<Transition.RequireSubJourney>()
                transition.journey shouldBe ReIdentifyJourney
            }
        }
    }

    given("AfterIdentification, back from RE_IDENTIFY") {
        val state = WebSelectMethodState.AfterIdentification

        `when`("the identification reached the floor") {
            val identified = evidence(listOf("sms", "fsc"), setOf(FactorType.POSSESSION), account = smsAndDevice)
            val theCtx = webCtx(account = smsAndDevice, evidence = identified, acrFloor = AcrLevel.LOA2)
            val transition = journey.transition(state, JourneyEvent.SubJourneyFinished(ReIdentifyJourney, achievedAcr = AcrLevel.LOA2), theCtx)

            then("it is authenticated") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})
