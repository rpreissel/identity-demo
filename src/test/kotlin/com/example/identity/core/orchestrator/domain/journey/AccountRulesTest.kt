package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The account rules of the acting phase, as tables - no Spring, no database
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md). The integration tests run the same rules end to end.
 */
class AccountRulesTest : BehaviorSpec({

    fun method(id: String) = AuthMethodView(id, "sms", true, null, "loa1", null, EnrollmentRef("sms", id))
    fun account(id: Long, personId: PartnerNumber? = null, methods: List<AuthMethodView> = emptyList()) =
        AccountProfile(accountId = AccountId(id), personId = personId, authenticationMethods = methods)

    val disposable = account(1)
    val interessent = account(2, methods = listOf(method("m2")))
    val identified = account(3, personId = PartnerNumber("P000000003"), methods = listOf(method("m3")))

    given("an identification that resolved to no existing account") {
        then("with nothing in hand it opens a new account") {
            IdentificationTarget.forUnresolved(null) { error("not asked") } shouldBe IdentificationTarget.NewAccount
        }
        then("an unidentified account in hand takes it - as the same person only") {
            IdentificationTarget.forUnresolved(interessent) { true } shouldBe IdentificationTarget.AccountInHand(AccountId(2))
            shouldThrow<IdentityConflictException> { IdentificationTarget.forUnresolved(interessent) { false } }
        }
        then("an identified account in hand refuses it - that would be a second person") {
            shouldThrow<IdentityConflictException> { IdentificationTarget.forUnresolved(identified) { true } }
        }
    }

    given("two accounts meeting in one run (ADR-20)") {
        then("the disposable one in hand moves into the resolved one - also when both are disposable") {
            AccountMerge.decide(disposable) { identified } shouldBe AccountMerge.MoveInto(from = AccountId(1), into = AccountId(3))
            AccountMerge.decide(disposable) { account(4) } shouldBe AccountMerge.MoveInto(from = AccountId(1), into = AccountId(4))
        }
        then("a disposable resolved one is absorbed into the one in hand") {
            AccountMerge.decide(interessent) { account(5) } shouldBe AccountMerge.AbsorbResolved(resolved = AccountId(5), into = AccountId(2))
        }
        then("two real accounts are never merged by an identification") {
            shouldThrow<IdentityConflictException> { AccountMerge.decide(interessent) { identified } }
        }
    }

    given("a correlation step (ADR-18)") {
        then("it passes only for an unbound account and a matching person") {
            checkCorrelation(interessent, ToolId("ident-kvnr"), PartnerNumber("P000000009")) { it == PartnerNumber("P000000009") }
            shouldThrow<IdentityConflictException> { checkCorrelation(interessent, ToolId("ident-kvnr"), PartnerNumber("P000000009")) { false } }
        }
        then("an account that already has a person is refused before anything is compared") {
            shouldThrow<IdentityConflictException> { checkCorrelation(identified, ToolId("ident-kvnr"), PartnerNumber("P000000003")) { true } }
        }
        then("a correlation that resolved nobody is a broken tool, not a case") {
            shouldThrow<IllegalStateException> { checkCorrelation(interessent, ToolId("ident-kvnr"), null) { true } }
        }
    }

    given("an attested address that belongs to another account") {
        fun evidence(vararg axes: EvidenceAxis) = SessionEvidence(axes.map {
            MethodEvidence(MethodName("m"), AcrLevel.LOA2, source = "ORCHESTRATOR", amrSourceId = "t", axis = it)
        })
        then("without an identification in this session it never moves the session") {
            shouldThrow<IdentityConflictException> { checkAttestationMove(evidence(EvidenceAxis.AUTHENTICATOR), null) { true } }
        }
        then("after one, it moves only to an account whose person the attested identity fits") {
            checkAttestationMove(evidence(EvidenceAxis.IDENTITY), null) { error("no person to compare") }
            checkAttestationMove(evidence(EvidenceAxis.IDENTITY), PartnerNumber("P000000003")) { true }
            shouldThrow<IdentityConflictException> { checkAttestationMove(evidence(EvidenceAxis.IDENTITY), PartnerNumber("P000000003")) { false } }
        }
    }

    given("a proven credential") {
        then("only a lookup tool may name the account, and only one agreeing with the account in hand") {
            accountOfProof(ToolRole.ACCOUNT_LOOKUP_AUTH, namedByTool = AccountId(7), inHand = null) shouldBe AccountId(7)
            accountOfProof(ToolRole.ACCOUNT_LOOKUP_AUTH, namedByTool = AccountId(7), inHand = AccountId(7)) shouldBe AccountId(7)
            shouldThrow<IdentityConflictException> { accountOfProof(ToolRole.ACCOUNT_LOOKUP_AUTH, namedByTool = AccountId(7), inHand = AccountId(8)) }
        }
        then("any other role proves the account in hand, whatever it names") {
            accountOfProof(ToolRole.KNOWN_ACCOUNT_AUTH, namedByTool = AccountId(7), inHand = AccountId(8)) shouldBe AccountId(8)
        }
    }
})
