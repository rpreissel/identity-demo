package com.example.identity.core.orchestrator.session

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * How [SessionEvidenceRecord] adds proofs: a completed tool's, or one taken over from an earlier flow
 * run of the same Keycloak session (ADR-59). It only adds, never removes (ADR-58: nobody reports a set that replaces one).
 */
class SessionEvidenceRecordTest : BehaviorSpec({

    fun proof(method: String, factorType: FactorType = FactorType.KNOWLEDGE, provenAt: Instant? = null) = MethodEvidence(
        MethodName(method), AcrLevel.LOA1, factorTypes = setOf(factorType), amrSourceId = "auth-$method", provenAt = provenAt
    )

    fun evidence(vararg proofs: String) = SessionEvidenceRecord(Subject.Account(AccountId(1L)), TEST_NOW).apply {
        addAmr(proofs.map { proof(it) }, TEST_NOW)
    }

    given("evidence with a password proof") {
        val record = evidence("password")

        `when`("sms is proven too") {
            record.addAmr(listOf(proof("sms", FactorType.POSSESSION)), TEST_NOW)

            then("both are kept, and their factor kinds add up") {
                record.currentAmr.toSet() shouldBe setOf("password", "sms")
                record.currentFactorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            }
        }

        `when`("a later proof names only sms") {
            record.addAmr(listOf(proof("sms", FactorType.POSSESSION)), TEST_NOW)

            then("password is not dropped") {
                record.currentAmr.toSet() shouldBe setOf("password", "sms")
            }
        }
    }

    given("an empty record") {
        val record = evidence()

        `when`("a restored proof arrives with its original time") {
            val provenAt = TEST_NOW.minus(Duration.ofMinutes(20))
            record.addAmr(listOf(proof("password", provenAt = provenAt)), TEST_NOW)

            then("it keeps its age instead of counting as proven just now") {
                record.methods.single().provenAt shouldBe provenAt
            }
        }
    }
})
