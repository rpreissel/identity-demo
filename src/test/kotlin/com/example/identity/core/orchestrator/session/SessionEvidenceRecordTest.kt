package com.example.identity.core.orchestrator.session

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.AmrSource
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * How [SessionEvidenceRecord] merges the proofs of its two sources (docs/05-api.md Abschnitt 3b):
 * Keycloak reports its complete currently valid set each time, the orchestrator adds single proofs,
 * and a verified orchestrator proof is never downgraded to Keycloak's unverified report (ADR-7).
 */
class SessionEvidenceRecordTest : BehaviorSpec({

    fun proof(method: String, source: String, factorType: FactorType = FactorType.KNOWLEDGE) = MethodEvidence(
        MethodName(method), AcrLevel.LOA1, factorTypes = setOf(factorType), source = source, amrSourceId = "$source:$method"
    )

    fun evidence(vararg orchestratorProofs: String) = SessionEvidenceRecord(Subject.Account(AccountId(1L)), TEST_NOW).apply {
        addAmr(orchestratorProofs.map { proof(it, AmrSource.ORCHESTRATOR) }, TEST_NOW)
    }

    given("evidence whose password an orchestrator tool proved") {
        val record = evidence("password")

        `when`("Keycloak re-reports password in its set (a naive full-list resend)") {
            record.replaceForSource(AmrSource.KEYCLOAK, listOf(proof("password", AmrSource.KEYCLOAK)), TEST_NOW)

            then("its source stays orchestrator - the stronger, verified claim is never downgraded to kc") {
                record.currentAmrSource shouldBe mapOf("password" to AmrSource.ORCHESTRATOR)
            }
        }
    }

    given("evidence without any proof") {
        val record = evidence()

        `when`("Keycloak reports sms") {
            record.replaceForSource(AmrSource.KEYCLOAK, listOf(proof("sms", AmrSource.KEYCLOAK, FactorType.POSSESSION)), TEST_NOW)

            then("sms is tagged kc - Keycloak's session vouches for it, not a tool of this channel") {
                record.currentAmrSource shouldBe mapOf("sms" to AmrSource.KEYCLOAK)
            }
        }
    }

    given("evidence with sms from Keycloak") {
        val record = evidence().apply {
            replaceForSource(AmrSource.KEYCLOAK, listOf(proof("sms", AmrSource.KEYCLOAK, FactorType.POSSESSION)), TEST_NOW)
        }

        `when`("Keycloak's next set holds sms and password") {
            record.replaceForSource(
                AmrSource.KEYCLOAK,
                listOf(proof("sms", AmrSource.KEYCLOAK, FactorType.POSSESSION), proof("password", AmrSource.KEYCLOAK)),
                TEST_NOW
            )

            then("both methods are tagged kc, and their factor kinds add up") {
                record.currentAmrSource shouldBe mapOf("sms" to AmrSource.KEYCLOAK, "password" to AmrSource.KEYCLOAK)
                record.currentFactorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            }
        }
    }

    given("evidence with sms and password from Keycloak, and email from an orchestrator tool") {
        val record = evidence("email").apply {
            replaceForSource(AmrSource.KEYCLOAK, listOf(proof("sms", AmrSource.KEYCLOAK), proof("password", AmrSource.KEYCLOAK)), TEST_NOW)
        }

        `when`("Keycloak's next set no longer holds sms") {
            record.replaceForSource(AmrSource.KEYCLOAK, listOf(proof("password", AmrSource.KEYCLOAK)), TEST_NOW)

            then("sms has expired and goes, the orchestrator's proof stays") {
                record.currentAmrSource shouldBe mapOf("email" to AmrSource.ORCHESTRATOR, "password" to AmrSource.KEYCLOAK)
            }
        }
    }

    given("evidence whose password only Keycloak reported") {
        val record = evidence().apply { replaceForSource(AmrSource.KEYCLOAK, listOf(proof("password", AmrSource.KEYCLOAK)), TEST_NOW) }

        `when`("an orchestrator tool proves password") {
            record.addAmr(listOf(proof("password", AmrSource.ORCHESTRATOR)), TEST_NOW)

            then("the verified proof takes over the method") {
                record.currentAmrSource shouldBe mapOf("password" to AmrSource.ORCHESTRATOR)
            }
        }
    }
})
