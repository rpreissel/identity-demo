package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.KcSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.webTools
import com.example.identity.tools.auth_password.AuthPasswordLookupDescriptor
import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
import com.example.identity.tools.auth_sms.AuthSmsLookupDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit test of [KcSelectMethodStrategy] (docs/journeys/kc-select-method.md) on the Web channel:
 * lookup-login tools without an account, auth tools for a known one, a finished journey once the
 * evidence meets the floor, and no identification or enrollment.
 */
class KcSelectMethodStrategyTest : BehaviorSpec({

    val strategy = KcSelectMethodStrategy()
    val lookupTools = listOf("auth-sms-lookup", "auth-password-lookup", "auth-qr-lookup", "auth-invite").map(::ToolId)

    fun webCtx(
        account: AccountProfile? = null,
        evidence: AuthEvidence = AuthEvidence(emptyList()),
        acrFloor: AcrLevel = AcrLevel.LOA1
    ) = ctx(account = account, evidence = evidence, acrFloor = acrFloor, availableTools = webTools, channel = ChannelType.KEYCLOAK)

    given("the intent") {
        then("is KC_SELECT_METHOD") {
            strategy.intent shouldBe AuthIntent.KC_SELECT_METHOD
        }
    }

    given("initialState without an account") {
        then("offers the Web channel's lookup-login tools") {
            val state = strategy.initialState(webCtx())
            state.shouldBeInstanceOf<KcSelectMethodState.SelectMethod>()
            state.offer.offered shouldContainExactlyInAnyOrder lookupTools
            state.accountAlreadyKnown shouldBe false
        }
    }

    given("initialState with a known account (a Web step-up)") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1))
        val theCtx = webCtx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)

        then("offers only auth tools for that account") {
            val state = strategy.initialState(theCtx)
            state.shouldBeInstanceOf<KcSelectMethodState.SelectMethod>()
            state.accountAlreadyKnown shouldBe true
            state.offer.offered.shouldNotBeEmpty()
            state.offer.offered.map { theCtx.catalog.descriptors().first { d -> d.toolId == it }.role }.toSet() shouldBe
                setOf(MethodRole.IDENTIFIED_AUTH)
        }
    }

    given("SelectMethod without an account") {
        val state = KcSelectMethodState.SelectMethod(Offer(lookupTools), accountAlreadyKnown = false)

        `when`("the journey starts") {
            val transition = strategy.transition(state, JourneyEvent.Started, webCtx())

            then("it keeps offering the lookup-login tools") {
                transition shouldBe Transition.To(KcSelectMethodState.SelectMethod(Offer(state.offer.offered), accountAlreadyKnown = false))
            }
        }

        `when`("a lookup tool authenticates") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("password"), subject = Subject.Account(1L))
            val transition = strategy.transition(state, JourneyEvent.Completed(AuthPasswordLookupDescriptor, outcome), webCtx())

            then("it performs AcceptProof and resumes in the same state") {
                transition shouldBe Transition.Perform(Action.AcceptProof(AuthPasswordLookupDescriptor, outcome), resumeState = state)
            }
        }

        `when`("an enrollment outcome arrives, which this intent never offers") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("auth_password.enrollment", "1"))
            val result = runCatching { strategy.transition(state, JourneyEvent.Completed(EnrollPasswordDescriptor, outcome), webCtx()) }

            then("it fails loudly with IllegalStateException") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("one of several offered tools is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsLookupDescriptor), webCtx())

            then("it keeps the choice among the rest") {
                transition shouldBe Transition.To(state.declining(ToolId("auth-sms-lookup")))
            }
        }
    }

    given("SelectMethod with a single offered tool") {
        val state = KcSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-sms-lookup"))), accountAlreadyKnown = false)

        `when`("that tool is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsLookupDescriptor), webCtx())

            then("it cancels") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("SelectMethod, the account known and the evidence meeting the floor") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = webCtx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = KcSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-sms"))), accountAlreadyKnown = true)

        `when`("the journey starts, e.g. with restored evidence") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)

            then("it is authenticated at once") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("the accepted proof has been booked (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)

            then("it is authenticated") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("SelectMethod, the account known but the evidence below the floor") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1))
        val theCtx = webCtx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)
        val state = KcSelectMethodState.SelectMethod(Offer(listOf(ToolId("auth-password"))), accountAlreadyKnown = true)

        `when`("Keycloak reports more evidence that still falls short") {
            val transition = strategy.transition(state, JourneyEvent.EvidenceReported, theCtx)

            then("it offers the account's auth tools again") {
                transition shouldBe Transition.To(strategy.initialState(theCtx))
                val next = (transition as Transition.To).state
                next.shouldBeInstanceOf<KcSelectMethodState.SelectMethod>()
                next.accountAlreadyKnown shouldBe true
            }
        }
    }
})
