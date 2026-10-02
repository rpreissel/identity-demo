package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.emailAttestation
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.identifiedOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Unit test of [AuthEnrollCore.proofAction], the outcome-to-action mapping [FastAccessStrategy] and
 * [RegisterStrategy] share. The strategy tests only check that each offering state hands a
 * completed tool to it.
 */
class AuthEnrollCoreTest : BehaviorSpec({

    given("an identification tool reported a person") {
        val event = JourneyEvent.Completed(tool("ident-fsc"), identifiedOutcome())

        `when`("the action is chosen") {
            val action = AuthEnrollCore.proofAction(event)

            then("it records the identification, which finds or creates the account") {
                action shouldBe Action.RecordIdentification(tool("ident-fsc"), identifiedOutcome())
            }
        }
    }

    given("an enrollment tool created a credential") {
        val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
        val event = JourneyEvent.Completed(tool("enroll-sms"), outcome)

        `when`("the action is chosen") {
            val action = AuthEnrollCore.proofAction(event)

            then("it adopts the credential") {
                action shouldBe Action.AdoptCredential(tool("enroll-sms"), outcome)
            }
        }
    }

    given("an auth tool proved an existing credential") {
        val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
        val event = JourneyEvent.Completed(tool("auth-sms"), outcome)

        `when`("the action is chosen") {
            val action = AuthEnrollCore.proofAction(event)

            then("it accepts the proof") {
                action shouldBe Action.AcceptProof(tool("auth-sms"), outcome)
            }
        }
    }

    given("an attesting tool confirmed an address") {
        val outcome = emailAttestation()
        val event = JourneyEvent.Completed(tool("confirm-email"), outcome)

        `when`("the action is chosen") {
            val action = AuthEnrollCore.proofAction(event)

            then("it adopts the attestation, without a credential") {
                action shouldBe Action.AdoptAttestation(tool("confirm-email"), outcome)
            }
        }
    }

    given("a peer-login tool approved another channel's login") {
        val event = JourneyEvent.Completed(tool("approve-qr"), ToolOutcome.Completed.Approved())

        `when`("the action is chosen") {
            val result = runCatching { AuthEnrollCore.proofAction(event) }

            then("it is refused, since FAST_ACCESS and REGISTER never offer such a tool") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
