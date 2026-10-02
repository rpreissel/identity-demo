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
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The account rules of the acting phase - no Spring, no database
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md). The integration tests run the same rules end to end.
 */
class AccountRulesTest : BehaviorSpec({

    fun method(id: String) = AuthMethodView(id, "sms", true, null, "loa1", null, null, EnrollmentRef("sms", id))
    fun account(id: Long, personId: PartnerNumber? = null, methods: List<AuthMethodView> = emptyList()) =
        AccountProfile(accountId = AccountId(id), personId = personId, authenticationMethods = methods)

    val disposable = account(1)
    val interessent = account(2, methods = listOf(method("m2")))
    val identified = account(3, personId = PartnerNumber("P000000003"), methods = listOf(method("m3")))

    // An identification that resolved to no existing account.
    given("an unresolved identification, no account in hand") {
        `when`("the target is chosen") {
            val target = IdentificationTarget.forUnresolved(null) { error("not asked") }

            then("it opens a new account") {
                target shouldBe IdentificationTarget.NewAccount
            }
        }
    }

    given("an unresolved identification, an unidentified account in hand") {
        `when`("the attested identity fits the account") {
            val target = IdentificationTarget.forUnresolved(interessent) { true }

            then("it takes the account in hand") {
                target shouldBe IdentificationTarget.AccountInHand(AccountId(2))
            }
        }

        `when`("the attested identity does not fit") {
            val result = runCatching { IdentificationTarget.forUnresolved(interessent) { false } }

            then("it refuses - only the same person may take it") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    given("an unresolved identification, an identified account in hand") {
        `when`("the target is chosen") {
            val result = runCatching { IdentificationTarget.forUnresolved(identified) { true } }

            then("it refuses - that would be a second person") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    // Two accounts meeting in one run (ADR-20).
    given("a disposable account in hand") {
        `when`("it meets an identified account") {
            val merge = AccountMerge.decide(disposable) { identified }

            then("the one in hand moves into the resolved one") {
                merge shouldBe AccountMerge.MoveInto(from = AccountId(1), into = AccountId(3))
            }
        }

        `when`("it meets another disposable account") {
            val merge = AccountMerge.decide(disposable) { account(4) }

            then("the one in hand moves into the resolved one all the same") {
                merge shouldBe AccountMerge.MoveInto(from = AccountId(1), into = AccountId(4))
            }
        }
    }

    given("a real account in hand") {
        `when`("it meets a disposable account") {
            val merge = AccountMerge.decide(interessent) { account(5) }

            then("the disposable one is absorbed into the one in hand") {
                merge shouldBe AccountMerge.AbsorbResolved(resolved = AccountId(5), into = AccountId(2))
            }
        }

        `when`("it meets another real account") {
            val result = runCatching { AccountMerge.decide(interessent) { identified } }

            then("they are never merged by an identification") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    // A correlation step (ADR-18).
    given("a correlation step on an account without a person") {
        val person = PartnerNumber("P000000009")

        `when`("the named person matches") {
            val result = runCatching { checkCorrelation(interessent, ToolId("ident-kvnr"), person) { it == person } }

            then("it passes") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }

        `when`("the named person does not match") {
            val result = runCatching { checkCorrelation(interessent, ToolId("ident-kvnr"), person) { false } }

            then("it is refused") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }

        `when`("the correlation resolved nobody") {
            val result = runCatching { checkCorrelation(interessent, ToolId("ident-kvnr"), null) { true } }

            then("it fails as a broken tool, not as a case") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("a correlation step on an account that already has a person") {
        `when`("the step is checked") {
            val result = runCatching { checkCorrelation(identified, ToolId("ident-kvnr"), PartnerNumber("P000000003")) { true } }

            then("it is refused before anything is compared") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    // An attested address that belongs to another account.
    fun evidence(axis: EvidenceAxis) = SessionEvidence(listOf(
        MethodEvidence(MethodName("m"), AcrLevel.LOA2, source = "ORCHESTRATOR", amrSourceId = "t", axis = axis)
    ))

    given("an attested address of another account, no identification in this session") {
        `when`("the session would move to that account") {
            val result = runCatching { checkAttestationMove(evidence(EvidenceAxis.AUTHENTICATOR), null) { true } }

            then("it never moves") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    given("an attested address of another account, an identification in this session") {
        val identifiedSession = evidence(EvidenceAxis.IDENTITY)

        `when`("the target account has no person") {
            val result = runCatching { checkAttestationMove(identifiedSession, null) { error("no person to compare") } }

            then("it moves without comparing") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }

        `when`("the target account's person fits the attested identity") {
            val result = runCatching { checkAttestationMove(identifiedSession, PartnerNumber("P000000003")) { true } }

            then("it moves") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }

        `when`("the target account's person does not fit") {
            val result = runCatching { checkAttestationMove(identifiedSession, PartnerNumber("P000000003")) { false } }

            then("it is refused") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    // A proven credential: only a lookup tool may name the account.
    given("a lookup tool that names account 7") {
        `when`("no account is in hand") {
            val account = accountOfProof(ToolRole.ACCOUNT_LOOKUP_AUTH, namedByTool = AccountId(7), inHand = null)

            then("the proof belongs to account 7") {
                account shouldBe AccountId(7)
            }
        }

        `when`("account 7 is in hand") {
            val account = accountOfProof(ToolRole.ACCOUNT_LOOKUP_AUTH, namedByTool = AccountId(7), inHand = AccountId(7))

            then("the proof belongs to account 7") {
                account shouldBe AccountId(7)
            }
        }

        `when`("another account is in hand") {
            val result = runCatching { accountOfProof(ToolRole.ACCOUNT_LOOKUP_AUTH, namedByTool = AccountId(7), inHand = AccountId(8)) }

            then("it is refused") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    given("a tool of another role that names account 7") {
        `when`("account 8 is in hand") {
            val account = accountOfProof(ToolRole.KNOWN_ACCOUNT_AUTH, namedByTool = AccountId(7), inHand = AccountId(8))

            then("the proof belongs to the account in hand, whatever the tool names") {
                account shouldBe AccountId(8)
            }
        }
    }
})
