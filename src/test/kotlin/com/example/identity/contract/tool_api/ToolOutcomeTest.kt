package com.example.identity.contract.tool_api

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.tools.auth_email.ConfirmEmailDescriptor
import com.example.identity.tools.ident_kvnr.IdentKvnrDescriptor
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Which outcome variant fits which role. The orchestrator rejects any other pairing as a contract
 * error of the tool module, so a wrong row here is a 500 on a real tool's success path.
 */
class ToolOutcomeTest : BehaviorSpec({

    val email = Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.of(ConfirmEmailDescriptor.toolId), AcrLevel.LOA1)
    val completed: List<ToolOutcome.Completed> = listOf(
        ToolOutcome.Completed.Identified(),
        ToolOutcome.Completed.Enrolled(EnrollmentRef("t", "1")),
        ToolOutcome.Completed.Attested(listOf(email)),
        ToolOutcome.Completed.Authenticated(amr = listOf("sms")),
        ToolOutcome.Completed.Approved(),
    )
    val failed: List<ToolOutcome.Failed> = listOf(
        ToolOutcome.Failed.IdentifiedAuth(Text("x")),
        ToolOutcome.Failed.LookupAuth(Text("x"), attempted = null),
        ToolOutcome.Failed.Identification(Text("x"), attemptedPersonId = null),
        ToolOutcome.Failed.NothingGuessed(Text("x")),
    )

    fun completedFitting(role: MethodRole) = completed.filter { it.fits(role) }.map { it::class }
    fun failedFitting(role: MethodRole) = failed.filter { it.fits(role) }.map { it::class }

    given("the success variants") {
        then("identification and correlation both answer with Identified") {
            completedFitting(MethodRole.IDENTIFICATION) shouldBe listOf(ToolOutcome.Completed.Identified::class)
            completedFitting(MethodRole.CORRELATION) shouldBe listOf(ToolOutcome.Completed.Identified::class)
        }
        then("an attestation answers with Attested only, never with Identified") {
            completedFitting(MethodRole.ATTESTATION) shouldBe listOf(ToolOutcome.Completed.Attested::class)
        }
        then("each remaining role has exactly its own variant") {
            completedFitting(MethodRole.ENROLLMENT) shouldBe listOf(ToolOutcome.Completed.Enrolled::class)
            completedFitting(MethodRole.IDENTIFIED_AUTH) shouldBe listOf(ToolOutcome.Completed.Authenticated::class)
            completedFitting(MethodRole.LOOKUP_AUTH) shouldBe listOf(ToolOutcome.Completed.Authenticated::class)
            completedFitting(MethodRole.PEER_APPROVAL) shouldBe listOf(ToolOutcome.Completed.Approved::class)
        }
    }

    given("the failure variants") {
        then("each role has exactly one") {
            MethodRole.entries.forEach { role -> failedFitting(role).size shouldBe 1 }
        }
        then("nothing is guessed by enrollment, attestation and peer approval") {
            listOf(MethodRole.ENROLLMENT, MethodRole.ATTESTATION, MethodRole.PEER_APPROVAL).forEach { role ->
                failedFitting(role) shouldBe listOf(ToolOutcome.Failed.NothingGuessed::class)
            }
        }
    }

    given("the real descriptors") {
        then("confirm-email may attest, but not identify") {
            ToolOutcome.Completed.Attested(listOf(email)).fits(ConfirmEmailDescriptor.role) shouldBe true
            ToolOutcome.Completed.Identified().fits(ConfirmEmailDescriptor.role) shouldBe false
        }
        then("ident-kvnr, a correlation step, answers with Identified") {
            ToolOutcome.Completed.Identified().fits(IdentKvnrDescriptor.role) shouldBe true
        }
    }

    given("an attestation") {
        then("reports neither amr nor a level of its own") {
            val attested = ToolOutcome.Completed.Attested(listOf(email))
            attested.amr shouldBe emptyList()
            attested.achievedAcr shouldBe null
            attested.factorTypes shouldContainExactlyInAnyOrder emptySet()
        }
    }
})
