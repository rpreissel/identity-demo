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
import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * Unit test of [action] ([Action.of]), the one outcome-to-action mapping every strategy uses. The
 * strategy tests only check that each offering state hands a completed tool to it.
 */
class CompletedActionTest : BehaviorSpec({

    given("an identification tool reported a person") {
        val event = JourneyEvent.Completed(tool("ident-fsc"), identifiedOutcome())

        `when`("the action is chosen") {
            val action = event.action()

            then("it records the identification, which finds or creates the account") {
                action shouldBe Action.RecordIdentification(tool("ident-fsc"), identifiedOutcome())
            }
        }
    }

    given("an enrollment tool created a credential") {
        val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
        val event = JourneyEvent.Completed(tool("enroll-sms"), outcome)

        `when`("the action is chosen") {
            val action = event.action()

            then("it adopts the credential") {
                action shouldBe Action.AdoptCredential(tool("enroll-sms"), outcome)
            }
        }

        `when`("the action is chosen for a change in place, admitted at loa2") {
            val action = event.action(admittedAt = AcrLevel.LOA2)

            then("the credential is adopted under that level") {
                action shouldBe Action.AdoptCredential(tool("enroll-sms"), outcome, admittedAt = AcrLevel.LOA2)
            }
        }
    }

    given("an auth tool proved an existing credential") {
        val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
        val event = JourneyEvent.Completed(tool("auth-sms"), outcome)

        `when`("the action is chosen") {
            val action = event.action()

            then("it accepts the proof") {
                action shouldBe Action.AcceptProof(tool("auth-sms"), outcome)
            }
        }
    }

    given("an attesting tool confirmed an address") {
        val outcome = emailAttestation()
        val event = JourneyEvent.Completed(tool("confirm-email"), outcome)

        `when`("the action is chosen") {
            val action = event.action()

            then("it adopts the attestation, without a credential") {
                action shouldBe Action.AdoptAttestation(tool("confirm-email"), outcome)
            }
        }
    }

    given("a peer-login tool approved another channel's login") {
        val outcome = ToolOutcome.Completed.Approved()
        val event = JourneyEvent.Completed(tool("approve-qr"), outcome)

        `when`("the action is chosen") {
            val action = event.action()

            then("it records the approval - the tool already wrote it, this only audits") {
                action shouldBe Action.RecordApproval(tool("approve-qr"), outcome)
            }
        }
    }
})
