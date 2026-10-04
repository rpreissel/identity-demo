package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import java.time.Duration
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.tool_api.Proves
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolModule
import com.example.identity.contract.tool_api.toolModule
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.ClaimTrust
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Unit tests against a small synthetic catalog (ident-fsc/enroll-sms/auth-sms plus a hypothetical
 * passkey pair). The passkey covers two factor types, which exercises the MFA and capping rules of
 * docs/04-orchestrierung.md #2 beyond what the real catalog offers. Each given is one evidence or
 * account state, each when one policy call.
 */
class DefaultAuthPolicyTest : BehaviorSpec({

    /** One synthetic procedure playing [roles]; [optInEnrollment] makes its enrollment prove nothing (like qr). */
    fun module(
        method: String, factorTypes: Set<FactorType>, maxAcr: AcrLevel, vararg roles: ToolRole,
        onePerDevice: Boolean = false, optInEnrollment: Boolean = false,
    ): ToolModule = toolModule(
        method = method,
        name = Text("Test"),
        proves = Proves(factorTypes, maxAcr),
        onePerDevice = onePerDevice,
    ).apply {
        if (ToolRole.IDENTIFICATION in roles) identify("ident-$method", hint = Text("Test"))
        if (ToolRole.ENROLLMENT in roles) enroll("enroll-$method", hint = Text("Test"), optInOnly = optInEnrollment)
        if (ToolRole.KNOWN_ACCOUNT_AUTH in roles) login("auth-$method", hint = Text("Test"))
    }
    fun ToolModule.tool(role: ToolRole): Tool = tools.single { it.role == role }
    /** A catalog of the modules behind [tools]. */
    fun catalog(vararg tools: Tool) = ToolHandlerRegistry(tools.map { it.module }.distinct())
    /** A one-tool procedure, for the local catalogs below. */
    fun descriptor(role: ToolRole, method: String, factorTypes: Set<FactorType>, maxAcr: AcrLevel): Tool =
        module(method, factorTypes, maxAcr, role).tool(role)

    val fsc = module("fsc", setOf(FactorType.POSSESSION), AcrLevel.LOA2, ToolRole.IDENTIFICATION)
    val sms = module("sms", setOf(FactorType.POSSESSION), AcrLevel.LOA2, ToolRole.ENROLLMENT, ToolRole.KNOWN_ACCOUNT_AUTH)
    val passkey = module("passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), AcrLevel.LOA3, ToolRole.ENROLLMENT, ToolRole.KNOWN_ACCOUNT_AUTH)
    val identFsc = fsc.tool(ToolRole.IDENTIFICATION)
    val enrollSms = sms.tool(ToolRole.ENROLLMENT)
    val authSms = sms.tool(ToolRole.KNOWN_ACCOUNT_AUTH)
    val authPasskey = passkey.tool(ToolRole.KNOWN_ACCOUNT_AUTH)
    val enrollPasskey = passkey.tool(ToolRole.ENROLLMENT)

    val registry = ToolHandlerRegistry(listOf(fsc, sms, passkey))
    val policy = DefaultAuthPolicy(registry, TEST_CLOCK)

    fun candidates(
        evidence: SessionEvidence,
        requiredAcr: AcrLevel,
        account: AccountProfile? = null,
        bindingKeyRef: String? = null,
        linkedAccountId: AccountId? = null,
        availableTools: Set<ToolId>? = null
    ) = CandidateContext(evidence, requiredAcr, account, bindingKeyRef, linkedAccountId, availableTools)

    fun account(vararg methods: AuthMethodView) = AccountProfile(
        accountId = AccountId(1L), personId = PartnerNumber("P000000001"), authenticationMethods = methods.toList()
    )

    fun method(method: String, enrolledUnderAcr: AcrLevel, active: Boolean = true) =
        AuthMethodView(id = "$method-instance", method = method, active = active, createdAt = null, enrolledUnderAcr = enrolledUnderAcr.value, boundKeyRef = null, reference = null, enrollmentRef = EnrollmentRef("${method}_enrollment", "1"))

    val nothingProven = SessionEvidence(emptyList())
    val smsAtLoa2 = SessionEvidence.fromNow(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION), methodAcr = mapOf("sms" to AcrLevel.LOA2.value))

    // Evidence age --------------------------------------------------------------------------------

    given("an sms proof worth loa2, proven 29 minutes ago") {
        val acc = account(method("sms", enrolledUnderAcr = AcrLevel.LOA2))
        val evidence = smsAtLoa2.provenAt(TEST_NOW.minus(Duration.ofMinutes(29)))

        `when`("the level is resolved") {
            val acr = policy.resolveAcr(evidence, acc)

            then("it still carries loa2") {
                acr shouldBe AcrLevel.LOA2
            }
        }

        `when`("loa2 is checked") {
            val satisfied = policy.isSatisfied(evidence, AcrLevel.LOA2, acc)

            then("it is satisfied") {
                satisfied shouldBe true
            }
        }
    }

    given("an sms proof worth loa2, proven 31 minutes ago") {
        val acc = account(method("sms", enrolledUnderAcr = AcrLevel.LOA2))
        val evidence = smsAtLoa2.provenAt(TEST_NOW.minus(Duration.ofMinutes(31)))

        `when`("the level is resolved") {
            val acr = policy.resolveAcr(evidence, acc)

            then("it carries only loa1") {
                acr shouldBe AcrLevel.LOA1
            }
        }

        `when`("loa2 is checked") {
            val satisfied = policy.isSatisfied(evidence, AcrLevel.LOA2, acc)

            then("it is no longer satisfied") {
                satisfied shouldBe false
            }
        }

        `when`("loa1 is checked") {
            val satisfied = policy.isSatisfied(evidence, AcrLevel.LOA1, acc)

            then("it is satisfied") {
                satisfied shouldBe true
            }
        }

        `when`("auth candidates for loa2 are resolved") {
            val offered = policy.authCandidates(candidates(evidence, AcrLevel.LOA2, acc))

            then("the same method is offered again to prove it anew") {
                offered shouldContainExactly listOf(ToolId("auth-sms"))
            }
        }
    }

    given("an sms proof worth loa2 of unknown age") {
        val acc = account(method("sms", enrolledUnderAcr = AcrLevel.LOA2))
        val evidence = SessionEvidence(smsAtLoa2.methods.map { it.copy(provenAt = null) })

        `when`("the level is resolved") {
            val acr = policy.resolveAcr(evidence, acc)

            then("it carries only loa1") {
                acr shouldBe AcrLevel.LOA1
            }
        }
    }

    // Freshness for self-service --------------------------------------------------------------------

    given("two proofs, the younger one four minutes old") {
        val evidence = SessionEvidence(
            smsAtLoa2.provenAt(TEST_NOW.minus(Duration.ofMinutes(20))).methods +
                smsAtLoa2.provenAt(TEST_NOW.minus(Duration.ofMinutes(4))).methods
        )

        `when`("freshness is checked") {
            val fresh = policy.hasFreshProof(evidence)

            then("the youngest proof decides: it is fresh") {
                fresh shouldBe true
            }
        }
    }

    given("a proof six minutes old") {
        val evidence = smsAtLoa2.provenAt(TEST_NOW.minus(Duration.ofMinutes(6)))

        `when`("freshness is checked") {
            val fresh = policy.hasFreshProof(evidence)

            then("it is not fresh, though it still carries loa2") {
                fresh shouldBe false
                policy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
            }
        }
    }

    given("a proof of unknown age, and no proof at all") {
        val ageless = SessionEvidence(smsAtLoa2.methods.map { it.copy(provenAt = null) })

        `when`("freshness is checked") {
            val fresh = listOf(policy.hasFreshProof(ageless), policy.hasFreshProof(nothingProven))

            then("neither is fresh") {
                fresh shouldBe listOf(false, false)
            }
        }
    }

    // Level and MFA of the proven evidence ---------------------------------------------------------

    given("nothing proven yet") {
        `when`("the level is resolved") {
            val acr = policy.resolveAcr(nothingProven, account = null)

            then("it is none") {
                acr shouldBe AcrLevel.NONE
            }
        }

        `when`("re-identification candidates for loa2 are resolved") {
            val offered = policy.reIdentCandidates(candidates(nothingProven, AcrLevel.LOA2))

            then("only the IDENTIFICATION tool appears, never AUTH or ENROLL tools") {
                offered shouldContainExactly listOf(ToolId("ident-fsc"))
            }
        }

        `when`("re-identification candidates for loa3 are resolved") {
            val offered = policy.reIdentCandidates(candidates(nothingProven, AcrLevel.LOA3))

            then("an IDENT tool whose own maxAcr falls short is excluded") {
                // ident-fsc tops out at loa2 (see catalog above), so it cannot close a loa3 gap alone.
                offered.shouldBeEmpty()
            }
        }
    }

    given("sms proven at loa2 - a single possession factor") {
        `when`("the level is resolved") {
            val acr = policy.resolveAcr(smsAtLoa2, account = null)

            then("it is the method's maxAcr") {
                acr shouldBe AcrLevel.LOA2
            }
        }

        `when`("loa2 is checked") {
            val satisfied = policy.isSatisfied(smsAtLoa2, AcrLevel.LOA2, account = null)

            then("it is satisfied - the level alone counts, not MFA") {
                satisfied shouldBe true
            }
        }

        `when`("loa1 is checked") {
            val satisfied = policy.isSatisfied(smsAtLoa2, AcrLevel.LOA1, account = null)

            then("it is satisfied") {
                satisfied shouldBe true
            }
        }

        `when`("loa3 is checked") {
            val satisfied = policy.isSatisfied(smsAtLoa2, AcrLevel.LOA3, account = null)

            then("a single factor type is not enough") {
                satisfied shouldBe false
            }
        }
    }

    given("a passkey proven - two factor types from one tool") {
        val evidence = SessionEvidence.fromNow(amr = listOf("passkey"), factorTypes = setOf(FactorType.POSSESSION, FactorType.INHERENCE), methodAcr = mapOf("passkey" to AcrLevel.LOA3.value))

        `when`("the level is resolved") {
            val acr = policy.resolveAcr(evidence, account = null)

            then("it is the method's maxAcr") {
                acr shouldBe AcrLevel.LOA3
            }
        }

        `when`("loa3 is checked") {
            val satisfied = policy.isSatisfied(evidence, AcrLevel.LOA3, account = null)

            then("it is satisfied") {
                satisfied shouldBe true
            }
        }
    }

    given("two proofs of the same factor type") {
        val evidence = SessionEvidence.fromNow(
            amr = listOf("sms", "someOtherPossessionMethod"),
            factorTypes = setOf(FactorType.POSSESSION),
            methodAcr = mapOf("sms" to AcrLevel.LOA2.value, "someOtherPossessionMethod" to AcrLevel.LOA2.value)
        )

        `when`("loa3 is checked") {
            val satisfied = policy.isSatisfied(evidence, AcrLevel.LOA3, account = null)

            then("MFA is never satisfied") {
                satisfied shouldBe false
            }
        }
    }

    given("fsc already proven this session") {
        val alreadyIdentified = SessionEvidence.fromNow(listOf("fsc"), setOf(FactorType.POSSESSION))

        `when`("re-identification candidates for loa2 are resolved") {
            val offered = policy.reIdentCandidates(candidates(alreadyIdentified, AcrLevel.LOA2))

            then("the IDENT tool already used is excluded, regardless of level") {
                offered.shouldBeEmpty()
            }
        }
    }

    given("ident-eid proven on its own - card and PIN on the IDENTITY axis") {
        val identEid = descriptor(ToolRole.IDENTIFICATION, "eid", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), AcrLevel.LOA3)
        val localPolicy = DefaultAuthPolicy(catalog(identEid), TEST_CLOCK)
        val evidence = SessionEvidence.fromNow(
            listOf("eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), mapOf("eid" to AcrLevel.LOA3.value),
            axis = mapOf("eid" to EvidenceAxis.IDENTITY)
        )

        `when`("the level is resolved") {
            val acr = localPolicy.resolveAcr(evidence, account = null)

            then("it is loa3") {
                acr shouldBe AcrLevel.LOA3
            }
        }

        `when`("loa3 is checked") {
            val satisfied = localPolicy.isSatisfied(evidence, AcrLevel.LOA3, account = null)

            then("MFA is satisfied on the IDENTITY axis alone") {
                satisfied shouldBe true
            }
        }
    }

    given("an fsc identification plus a password that claims a loa3 enrollment") {
        // The identification sits on the IDENTITY axis. An attacker who steals the password only
        // has to defeat the password, so fsc + password is one authenticator, not two, and gets no
        // MFA bump even with a claimed loa3 enrolledUnderAcr.
        val tokenPassword = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "password", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
        val localPolicy = DefaultAuthPolicy(catalog(identFsc, tokenPassword), TEST_CLOCK)
        val evidence = SessionEvidence.fromNow(
            amr = listOf("fsc", "password"),
            factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
            methodAcr = mapOf("fsc" to AcrLevel.LOA2.value, "password" to AcrLevel.LOA1.value),
            enrolledUnderAcr = mapOf("password" to AcrLevel.LOA3.value),
            axis = mapOf("fsc" to EvidenceAxis.IDENTITY, "password" to EvidenceAxis.AUTHENTICATOR)
        )

        `when`("the level is resolved") {
            val acr = localPolicy.resolveAcr(evidence, account = null)

            then("it stays at the identification's loa2 - no false MFA bump") {
                acr shouldBe AcrLevel.LOA2
            }
        }

        `when`("loa3 is checked") {
            val satisfied = localPolicy.isSatisfied(evidence, AcrLevel.LOA3, account = null)

            then("it is not satisfied") {
                satisfied shouldBe false
            }
        }
    }

    // Accounts: reachability and candidates --------------------------------------------------------

    given("an account without any active method") {
        val noMethods = account()

        `when`("reachability is checked") {
            val reachability = policy.reachability(noMethods, AcrLevel.LOA1)

            then("no active method at all is the reason") {
                reachability shouldBe Reachability.NotReachable(UnreachableReason.NoActiveMethod)
            }
        }

        `when`("enrollment candidates are resolved") {
            val offered = policy.enrollmentCandidates(candidates(nothingProven, AcrLevel.LOA2, noMethods))

            then("every enrollment tool is offered") {
                offered shouldContainExactlyInAnyOrder listOf(ToolId("enroll-sms"), ToolId("enroll-passkey"))
            }
        }
    }

    given("an account with sms enrolled under loa1") {
        val acc = account(method("sms", enrolledUnderAcr = AcrLevel.LOA1))

        `when`("reachability of loa2 is checked") {
            val reachability = policy.reachability(acc, AcrLevel.LOA2)

            then("the single factor type is named, checked before the enrolledUnderAcr cap") {
                reachability shouldBe
                    Reachability.NotReachable(UnreachableReason.SingleFactorType(listOf("sms"), setOf(FactorType.POSSESSION)))
            }
        }

        `when`("reachability of loa1 is checked") {
            val reachability = policy.reachability(acc, AcrLevel.LOA1)

            then("it is reachable") {
                reachability shouldBe Reachability.Reachable
            }
        }
    }

    given("an account with only sms enrolled under loa2, nothing proven yet") {
        val acc = account(method("sms", AcrLevel.LOA2))

        `when`("reachability of loa3 is checked") {
            val reachability = policy.reachability(acc, AcrLevel.LOA3)

            then("the single factor type is named as the blocker") {
                reachability shouldBe
                    Reachability.NotReachable(UnreachableReason.SingleFactorType(listOf("sms"), setOf(FactorType.POSSESSION)))
            }
        }

        `when`("enrollment candidates are resolved") {
            val offered = policy.enrollmentCandidates(candidates(nothingProven, AcrLevel.LOA2, acc))

            then("the already active method is excluded") {
                offered shouldContainExactly listOf(ToolId("enroll-passkey"))
            }
        }

        `when`("auth candidates are resolved on the App device linked to this account") {
            val offered = policy.authCandidates(candidates(nothingProven, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId))

            then("sms is offered") {
                offered shouldContainExactly listOf(ToolId("auth-sms"))
            }
        }

        `when`("auth candidates are resolved on a channel without a device key (Web)") {
            val offered = policy.authCandidates(candidates(nothingProven, AcrLevel.LOA2, acc, bindingKeyRef = null, linkedAccountId = null))

            then("sms is offered all the same - it is not bound to a key") {
                offered shouldContainExactly listOf(ToolId("auth-sms"))
            }
        }
    }

    given("an account with only sms enrolled under loa2, sms already proven this session") {
        val acc = account(method("sms", AcrLevel.LOA2))

        `when`("auth candidates are resolved") {
            val offered = policy.authCandidates(candidates(smsAtLoa2, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId))

            then("the method already used is excluded") {
                offered.shouldBeEmpty()
            }
        }
    }

    given("an account with a passkey enrolled under loa3") {
        val withPasskey = account(method("passkey", AcrLevel.LOA3))

        `when`("reachability of loa3 is checked") {
            val reachability = policy.reachability(withPasskey, AcrLevel.LOA3)

            then("the passkey's two factor types are enough on their own") {
                reachability shouldBe Reachability.Reachable
            }
        }
    }

    given("an account with a passkey enrolled under loa1 only") {
        val onlyPasskeyWeak = account(method("passkey", enrolledUnderAcr = AcrLevel.LOA1))

        `when`("reachability of loa3 is checked") {
            val reachability = policy.reachability(onlyPasskeyWeak, AcrLevel.LOA3)

            then("the enrolledUnderAcr cap is named, never 'needs another factor type'") {
                // `passkey` alone covers POSSESSION and INHERENCE, so "needs a different factor
                // type" would contradict itself here.
                reachability shouldBe Reachability.NotReachable(UnreachableReason.SingleMethodCapped("passkey", AcrLevel.LOA1))
            }
        }
    }

    given("sms and email active, each capped at loa1, and qr reaching loa2 alone, but the channel cannot offer qr") {
        // Mirrors the real catalog: auth-qr reaches loa2 alone, but the App never declares it in
        // availableTools. Regression for CONFIRM_PEER_LOGIN aborting for an account with sms and email.
        val tokenSms = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
        val tokenEmail = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "email", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
        val tokenQr = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "qr", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
        val localPolicy = DefaultAuthPolicy(catalog(tokenSms, tokenEmail, tokenQr), TEST_CLOCK)
        val acc = account(method("sms", AcrLevel.LOA2), method("email", AcrLevel.LOA2), method("qr", AcrLevel.LOA2))
        val context = candidates(
            nothingProven, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId,
            availableTools = setOf(ToolId("auth-sms"), ToolId("auth-email"))
        )

        `when`("auth candidates for loa2 are resolved") {
            val offered = localPolicy.authCandidates(context)

            then("the sms+email combination is offered - whether one method suffices is decided over the offerable ones only") {
                offered shouldContainExactlyInAnyOrder listOf(ToolId("auth-sms"), ToolId("auth-email"))
            }
        }
    }

    given("an account with a key-bound device credential on key-1") {
        val deviceAuth = module("device", setOf(FactorType.POSSESSION), AcrLevel.LOA2, ToolRole.KNOWN_ACCOUNT_AUTH, onePerDevice = true)
            .tool(ToolRole.KNOWN_ACCOUNT_AUTH)
        val devicePolicy = DefaultAuthPolicy(catalog(deviceAuth), TEST_CLOCK)
        val deviceMethod = AuthMethodView(
            id = "device-instance", method = "device", active = true, createdAt = null,
            enrolledUnderAcr = AcrLevel.LOA2.value, boundKeyRef = "key-1", reference = null,
            enrollmentRef = EnrollmentRef("auth_device.enrollment", "1")
        )
        val acc = account(deviceMethod)

        `when`("auth candidates are resolved on key-1, still linked to this account") {
            val offered = devicePolicy.authCandidates(candidates(nothingProven, AcrLevel.LOA2, acc, "key-1", linkedAccountId = acc.accountId))

            then("the device credential is offered") {
                offered shouldContainExactly listOf(ToolId("auth-device"))
            }
        }

        `when`("auth candidates are resolved on key-1, rebound to a different account") {
            val offered = devicePolicy.authCandidates(candidates(nothingProven, AcrLevel.LOA2, acc, "key-1", linkedAccountId = AccountId(999L)))

            then("it is not offered") {
                offered.shouldBeEmpty()
            }
        }

        `when`("auth candidates are resolved on another key, linked to this account") {
            val offered = devicePolicy.authCandidates(candidates(nothingProven, AcrLevel.LOA2, acc, "key-2", linkedAccountId = acc.accountId))

            then("it is not offered - the credential lives on key-1 only") {
                offered.shouldBeEmpty()
            }
        }
    }

    // One method whose enrollment proves nothing and whose auth tool covers two factor types (like
    // qr): the reachability counts what proving the method gives - the module's, not one tool's.
    given("method x, whose enrollment is an opt-in and whose auth tool proves two factor types") {
        val x = module("x", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), AcrLevel.LOA2,
            ToolRole.ENROLLMENT, ToolRole.KNOWN_ACCOUNT_AUTH, optInEnrollment = true)
        val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(x)), TEST_CLOCK)
        val acc = account(method("x", enrolledUnderAcr = AcrLevel.LOA2))

        `when`("reachability of loa2 is checked") {
            val reachability = localPolicy.reachability(acc, AcrLevel.LOA2)

            then("it is reachable through the auth tool's factor types") {
                reachability shouldBe Reachability.Reachable
            }
        }
    }

    // MFA combination capped by enrolledUnderAcr ----------------------------------------------------

    // Two loa1-only tools of different factor types (a = possession, b = knowledge). enrolledUnderAcr
    // is the caller's claim in SessionEvidence; AuthPolicy does not re-derive it from the account.
    val tokenA = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "a", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
    val tokenB = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "b", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
    val abPolicy = DefaultAuthPolicy(catalog(tokenA, tokenB), TEST_CLOCK)
    fun abEvidence(enrolledUnderAcr: Map<String, String>) = SessionEvidence.fromNow(
        amr = listOf("a", "b"), factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
        methodAcr = mapOf("a" to AcrLevel.LOA1.value, "b" to AcrLevel.LOA1.value), enrolledUnderAcr = enrolledUnderAcr
    )

    // Nothing vouches for loa2, so the combination stays at loa1: a compromised weak session must
    // not self-escalate by adding a second weak factor (docs/06-ablaeufe.md #1).
    given("a and b proven, both enrolled in a loa1-only session") {
        val bothWeak = account(method("a", enrolledUnderAcr = AcrLevel.LOA1), method("b", enrolledUnderAcr = AcrLevel.LOA1))
        val evidence = abEvidence(mapOf("a" to AcrLevel.LOA1.value, "b" to AcrLevel.LOA1.value))

        `when`("the level is resolved") {
            val acr = abPolicy.resolveAcr(evidence, bothWeak)

            then("the MFA bump is capped at loa1") {
                acr shouldBe AcrLevel.LOA1
            }
        }

        `when`("loa2 is checked") {
            val satisfied = abPolicy.isSatisfied(evidence, AcrLevel.LOA2, bothWeak)

            then("it is not satisfied") {
                satisfied shouldBe false
            }
        }

        `when`("reachability of loa2 is checked") {
            val reachability = abPolicy.reachability(bothWeak, AcrLevel.LOA2)

            then("the enrolledUnderAcr cap is named as the blocker") {
                reachability shouldBe Reachability.NotReachable(UnreachableReason.CombinationCapped(AcrLevel.LOA1))
            }
        }
    }

    given("a and b proven, a enrolled right after a loa2 session (e.g. an identification)") {
        val oneVouched = account(method("a", enrolledUnderAcr = AcrLevel.LOA2), method("b", enrolledUnderAcr = AcrLevel.LOA1))
        val evidence = abEvidence(mapOf("a" to AcrLevel.LOA2.value, "b" to AcrLevel.LOA1.value))

        `when`("the level is resolved") {
            val acr = abPolicy.resolveAcr(evidence, oneVouched)

            then("the pair reaches loa2 together") {
                acr shouldBe AcrLevel.LOA2
            }
        }

        `when`("loa2 is checked") {
            val satisfied = abPolicy.isSatisfied(evidence, AcrLevel.LOA2, oneVouched)

            then("it is satisfied") {
                satisfied shouldBe true
            }
        }

        `when`("reachability of loa2 is checked") {
            val reachability = abPolicy.reachability(oneVouched, AcrLevel.LOA2)

            then("it is reachable") {
                reachability shouldBe Reachability.Reachable
            }
        }
    }

    given("a and b proven, without any enrolledUnderAcr claim") {
        val evidence = abEvidence(emptyMap())

        `when`("the level is resolved") {
            val acr = abPolicy.resolveAcr(evidence, account = null)

            then("the bump is conservatively withheld") {
                acr shouldBe AcrLevel.LOA1
            }
        }
    }

    // loa2 as this project's label for NIST 800-63B AAL2 (docs/04-orchestrierung.md #8): "a
    // multi-factor authenticator, or a combination of two single-factor authenticators". Both paths
    // reach loa2, and so does an identification.

    given("two single-factor AUTH tools of different kinds proven, enrolled under loa2, no identification") {
        val tokenSms = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
        val tokenPassword = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "password", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
        val localPolicy = DefaultAuthPolicy(catalog(tokenSms, tokenPassword), TEST_CLOCK)
        val evidence = SessionEvidence.fromNow(
            amr = listOf("sms", "password"),
            factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
            methodAcr = mapOf("sms" to AcrLevel.LOA1.value, "password" to AcrLevel.LOA1.value),
            enrolledUnderAcr = mapOf("sms" to AcrLevel.LOA2.value, "password" to AcrLevel.LOA2.value)
        )

        `when`("the level is resolved") {
            val acr = localPolicy.resolveAcr(evidence, account = null)

            then("NIST's 'two single-factor authenticators' path reaches loa2 (AAL2)") {
                acr shouldBe AcrLevel.LOA2
            }
        }

        `when`("loa2 is checked") {
            val satisfied = localPolicy.isSatisfied(evidence, AcrLevel.LOA2, account = null)

            then("it is satisfied") {
                satisfied shouldBe true
            }
        }
    }

    given("a single AUTH tool proven that declares two factor types itself (device-like: possession+knowledge)") {
        val tokenDevice = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "device", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), AcrLevel.LOA2)
        val localPolicy = DefaultAuthPolicy(catalog(tokenDevice), TEST_CLOCK)
        val evidence = SessionEvidence.fromNow(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), mapOf("device" to AcrLevel.LOA2.value))

        `when`("the level is resolved") {
            val acr = localPolicy.resolveAcr(evidence, account = null)

            then("NIST's 'multi-factor authenticator' path reaches loa2 (AAL2) alone") {
                acr shouldBe AcrLevel.LOA2
            }
        }

        `when`("loa2 is checked") {
            val satisfied = localPolicy.isSatisfied(evidence, AcrLevel.LOA2, account = null)

            then("it is satisfied") {
                satisfied shouldBe true
            }
        }
    }

    given("only an identification proven this session (fsc), no AUTH factor at all") {
        val identOnly = SessionEvidence.fromNow(
            listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to AcrLevel.LOA2.value),
            axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
        )

        `when`("the level is resolved") {
            val acr = policy.resolveAcr(identOnly, account = null)

            then("it reaches loa2 on its own IAL, not via an MFA bump") {
                acr shouldBe AcrLevel.LOA2
            }
        }

        `when`("loa2 is checked") {
            val satisfied = policy.isSatisfied(identOnly, AcrLevel.LOA2, account = null)

            then("identification is an equally valid, not a lesser, path to the loa2/AAL2 threshold") {
                satisfied shouldBe true
            }
        }
    }

    given("two methods already rated loa2, of different factor types, each claiming a loa3 enrollment") {
        // NIST defines a combination rule only for AAL2. AAL3 requires a specific authenticator
        // technology (hardware-based, verifier-impersonation-resistant), so the generic bump stops
        // at loa2.
        val strongA = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "a", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
        val strongB = descriptor(ToolRole.KNOWN_ACCOUNT_AUTH, "b", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA2)
        val localPolicy = DefaultAuthPolicy(catalog(strongA, strongB), TEST_CLOCK)
        val evidence = SessionEvidence.fromNow(
            amr = listOf("a", "b"),
            factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
            methodAcr = mapOf("a" to AcrLevel.LOA2.value, "b" to AcrLevel.LOA2.value),
            enrolledUnderAcr = mapOf("a" to AcrLevel.LOA3.value, "b" to AcrLevel.LOA3.value)
        )

        `when`("the level is resolved") {
            val acr = localPolicy.resolveAcr(evidence, account = null)

            then("it stays at loa2, never bumps on to loa3") {
                acr shouldBe AcrLevel.LOA2
            }
        }
    }

    // requiresSatisfied - the generic Tool.requires gate ---------------------------------

    fun profile(vararg established: Pair<AttributeType, ClaimTrust>) = AccountProfile(
        accountId = AccountId(1L), personId = null, authenticationMethods = emptyList(),
        establishedClaims = established.toMap()
    )

    given("an account whose family name is PROVEN") {
        val acc = profile(AttributeType.FAMILY_NAME to ClaimTrust.PROVEN)

        `when`("the family name is required at PROVEN") {
            val satisfied = requiresSatisfied(ClaimRequirement(AttributeType.FAMILY_NAME, ClaimTrust.PROVEN), acc)

            then("the requirement is satisfied") {
                satisfied shouldBe true
            }
        }

        `when`("the birth date is required at PROVEN") {
            val satisfied = requiresSatisfied(ClaimRequirement(AttributeType.BIRTH_DATE, ClaimTrust.PROVEN), acc)

            then("an attribute the account does not hold - never established or retracted (ADR-12) - does not satisfy it") {
                satisfied shouldBe false
            }
        }
    }

    given("an account whose family name is AUTHORITATIVE") {
        val acc = profile(AttributeType.FAMILY_NAME to ClaimTrust.AUTHORITATIVE)

        `when`("the family name is required at PROVEN") {
            val satisfied = requiresSatisfied(ClaimRequirement(AttributeType.FAMILY_NAME, ClaimTrust.PROVEN), acc)

            then("the stronger source satisfies the weaker requirement") {
                satisfied shouldBe true
            }
        }
    }

    given("an account whose family name is only SELF_REPORTED") {
        val acc = profile(AttributeType.FAMILY_NAME to ClaimTrust.SELF_REPORTED)

        `when`("the family name is required at PROVEN") {
            val satisfied = requiresSatisfied(ClaimRequirement(AttributeType.FAMILY_NAME, ClaimTrust.PROVEN), acc)

            then("the weaker source does not satisfy the stronger requirement") {
                satisfied shouldBe false
            }
        }
    }

    given("no account at all") {
        `when`("an email is required at PROVEN") {
            val satisfied = requiresSatisfied(ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN), null)

            then("nothing is satisfied") {
                satisfied shouldBe false
            }
        }
    }
})
