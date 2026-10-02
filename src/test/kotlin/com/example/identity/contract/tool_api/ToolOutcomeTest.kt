package com.example.identity.contract.tool_api

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Which outcome variant fits which role. The orchestrator rejects any other pairing as a contract
 * error of the tool module, so a wrong row here is a 500 on a real tool's success path.
 */
class ToolOutcomeTest : BehaviorSpec({

    val email = Claim(AttributeType.EMAIL, "max@example.com", ClaimSource(tool("confirm-email").toolId.value), AcrLevel.LOA1)
    val completed: List<ToolOutcome.Completed> = listOf(
        ToolOutcome.Completed.Identified(),
        ToolOutcome.Completed.Enrolled(EnrollmentRef("t", "1")),
        ToolOutcome.Completed.Attested(listOf(email)),
        ToolOutcome.Completed.Authenticated(amr = listOf("sms")),
        ToolOutcome.Completed.Approved(),
    )
    val failed: List<ToolOutcome.Failed> = listOf(
        ToolOutcome.Failed.KnownAccountAuth(Text("x")),
        ToolOutcome.Failed.AccountLookupAuth(Text("x"), attempted = null),
        ToolOutcome.Failed.Identification(Text("x"), attemptedPersonId = null),
        ToolOutcome.Failed.NothingGuessed(Text("x")),
    )

    fun completedFitting(role: ToolRole) = completed.filter { it.fits(role) }.map { it::class }
    fun failedFitting(role: ToolRole) = failed.filter { it.fits(role) }.map { it::class }

    given("the success variants") {
        then("identification and correlation both answer with Identified") {
            completedFitting(ToolRole.IDENTIFICATION) shouldBe listOf(ToolOutcome.Completed.Identified::class)
            completedFitting(ToolRole.CORRELATION) shouldBe listOf(ToolOutcome.Completed.Identified::class)
        }
        then("an attestation answers with Attested only, never with Identified") {
            completedFitting(ToolRole.ATTESTATION) shouldBe listOf(ToolOutcome.Completed.Attested::class)
        }
        then("each remaining role has exactly its own variant") {
            completedFitting(ToolRole.ENROLLMENT) shouldBe listOf(ToolOutcome.Completed.Enrolled::class)
            completedFitting(ToolRole.KNOWN_ACCOUNT_AUTH) shouldBe listOf(ToolOutcome.Completed.Authenticated::class)
            completedFitting(ToolRole.ACCOUNT_LOOKUP_AUTH) shouldBe listOf(ToolOutcome.Completed.Authenticated::class)
            completedFitting(ToolRole.PEER_APPROVAL) shouldBe listOf(ToolOutcome.Completed.Approved::class)
        }
    }

    given("the failure variants") {
        then("each role has exactly one") {
            ToolRole.entries.forEach { role -> failedFitting(role).size shouldBe 1 }
        }
        then("nothing is guessed by enrollment, attestation and peer approval") {
            listOf(ToolRole.ENROLLMENT, ToolRole.ATTESTATION, ToolRole.PEER_APPROVAL).forEach { role ->
                failedFitting(role) shouldBe listOf(ToolOutcome.Failed.NothingGuessed::class)
            }
        }
    }

    given("the real descriptors") {
        then("confirm-email may attest, but not identify") {
            ToolOutcome.Completed.Attested(listOf(email)).fits(tool("confirm-email").role) shouldBe true
            ToolOutcome.Completed.Identified().fits(tool("confirm-email").role) shouldBe false
        }
        then("ident-kvnr, a correlation step, answers with Identified") {
            ToolOutcome.Completed.Identified().fits(tool("ident-kvnr").role) shouldBe true
        }
    }

    given("a run measured against its tool (auth-password: loa1, knowledge)") {
        fun run(acr: AcrLevel?, vararg factors: FactorType) =
            ToolOutcome.Completed.Authenticated(amr = listOf("password"), achievedAcr = acr, factorTypes = factors.toSet())

        then("the descriptor's own level and factor kind stay within it") {
            tool("auth-password").staysWithin(run(AcrLevel.LOA1, FactorType.KNOWLEDGE)) shouldBe true
        }
        then("a run without a level of its own stays within it") {
            tool("auth-password").staysWithin(run(null)) shouldBe true
        }
        then("a higher level goes beyond it") {
            tool("auth-password").staysWithin(run(AcrLevel.LOA2, FactorType.KNOWLEDGE)) shouldBe false
        }
        then("a factor kind the descriptor does not declare goes beyond it") {
            tool("auth-password").staysWithin(run(AcrLevel.LOA1, FactorType.KNOWLEDGE, FactorType.POSSESSION)) shouldBe false
        }
    }

    given("an attestation") {
        then("reports neither amr nor a level of its own, and its tool provides none") {
            val attested = ToolOutcome.Completed.Attested(listOf(email))
            attested.amr shouldBe null
            attested.achievedAcr shouldBe null
            tool("confirm-email").amrOf(attested).shouldBeEmpty()
            tool("confirm-email").factorsOf(attested).shouldBeEmpty()
        }
    }
})
