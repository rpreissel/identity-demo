package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.tool
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.emailAttestation
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.identifiedOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.Offer

/**
 * What a completed tool books ([com.example.identity.core.orchestrator.domain.journey.state.JourneyState.booking]):
 * [Action.of] for every state, except the two that say otherwise. `JourneyService` performs it
 * before any journey hears of the tool.
 */
class BookingTest : BehaviorSpec({

    given("an identification tool reported a person") {
        val event = Completion(tool("ident-fsc"), identifiedOutcome())

        `when`("the action is chosen") {
            val action = Action.of(event.tool, event.outcome)

            then("it records the identification, which finds or creates the account") {
                action shouldBe Action.RecordIdentification(tool("ident-fsc"), identifiedOutcome())
            }
        }
    }

    given("an enrollment tool created a credential") {
        val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
        val event = Completion(tool("enroll-sms"), outcome)

        `when`("the action is chosen") {
            val action = Action.of(event.tool, event.outcome)

            then("it adopts the credential") {
                action shouldBe Action.AdoptCredential(tool("enroll-sms"), outcome)
            }
        }

        `when`("it completed while changing a password in place") {
            val changing = ManageAuthMethodsState.Changing(Offer(listOf(ToolId("enroll-sms"))), methodInstanceId = "old-instance")
            val action = changing.booking(event.tool, outcome)

            then("the credential is adopted as the replacement of the old one, so it is never written lower") {
                action shouldBe Action.AdoptCredential(tool("enroll-sms"), outcome, replaces = "old-instance")
            }
        }
    }

    given("an auth tool proved an existing credential") {
        val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
        val event = Completion(tool("auth-sms"), outcome)

        `when`("the action is chosen") {
            val action = Action.of(event.tool, event.outcome)

            then("it accepts the proof") {
                action shouldBe Action.AcceptProof(tool("auth-sms"), outcome)
            }
        }

        `when`("it completed as FRESH_PROOF's re-proof") {
            val reproving = FreshProofState.Reproving(AcrLevel.LOA2, FreshProofState.Purpose.MANAGE_METHODS, Offer(listOf(ToolId("auth-sms"))))
            val action = reproving.booking(event.tool, outcome)

            then("it only reconfirms: the proof is for the caller's one action and never the session's evidence") {
                action shouldBe Action.Reconfirm(tool("auth-sms"), outcome)
            }
        }
    }

    given("an attesting tool confirmed an address") {
        val outcome = emailAttestation()
        val event = Completion(tool("confirm-email"), outcome)

        `when`("the action is chosen") {
            val action = Action.of(event.tool, event.outcome)

            then("it adopts the attestation, without a credential") {
                action shouldBe Action.AdoptAttestation(tool("confirm-email"), outcome)
            }
        }
    }

    given("a peer-login tool approved another channel's login") {
        val outcome = ToolOutcome.Completed.Approved()
        val event = Completion(tool("approve-qr"), outcome)

        `when`("the action is chosen") {
            val action = Action.of(event.tool, event.outcome)

            then("it records the approval - the tool already wrote it, this only audits") {
                action shouldBe Action.RecordApproval(tool("approve-qr"), outcome)
            }
        }
    }
})

/** A completed tool run as the service books it. */
private data class Completion(val tool: Tool, val outcome: ToolOutcome.Completed)
