package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import java.time.Duration
import com.example.identity.core.orchestrator.domain.policy.DefaultAuthPolicy
import com.example.identity.core.orchestrator.domain.policy.requiresSatisfied
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.policy.CandidateContext
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.core.orchestrator.domain.policy.UnreachableReason
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.CallerKeyBinding
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
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
 * docs/04-orchestrierung.md #2 beyond what the real catalog offers.
 */
class DefaultAuthPolicyTest : BehaviorSpec({

    fun descriptor(id: String, role: ToolRole, method: String, factorTypes: Set<FactorType>, maxAcr: AcrLevel): ToolDescriptor =
        object : ToolDescriptor {
            override val toolId = ToolId(id)
            override val role = role
            override val method = method
            override val factorTypes = factorTypes
            override val maxAcr = maxAcr
            // What the catalog demands of every identification (ToolHandlerRegistry, ADR-39).
            override val claims =
                if (role == ToolRole.IDENTIFICATION) setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
                    .map { ClaimDeclaration(it, ClaimSource(toolId.value)) }.toSet()
                else emptySet()
        }

    val identFsc = descriptor("ident-fsc", ToolRole.IDENTIFICATION, "fsc", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
    val enrollSms = descriptor("enroll-sms", ToolRole.ENROLLMENT, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
    val authSms = descriptor("auth-sms", ToolRole.KNOWN_ACCOUNT_AUTH, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
    val authPasskey = descriptor("auth-passkey", ToolRole.KNOWN_ACCOUNT_AUTH, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), AcrLevel.LOA3)
    val enrollPasskey = descriptor("enroll-passkey", ToolRole.ENROLLMENT, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), AcrLevel.LOA3)

    val registry = ToolHandlerRegistry(listOf(identFsc, enrollSms, authSms, authPasskey, enrollPasskey))
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
        AuthMethodView(id = "$method-instance", method = method, active = active, createdAt = null, enrolledUnderAcr = enrolledUnderAcr.value, details = null, enrollmentRef = EnrollmentRef("${method}_enrollment", "1"))

    given("an sms proof worth loa2, of a certain age") {
        val sms = SessionEvidence.fromNow(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION), methodAcr = mapOf("sms" to AcrLevel.LOA2.value))
        val acc = account(method("sms", enrolledUnderAcr = AcrLevel.LOA2))

        `when`("it was proven 29 minutes ago") {
            val evidence = sms.provenAt(TEST_NOW.minus(Duration.ofMinutes(29)))
            val acr = policy.resolveAcr(evidence, acc)
            val satisfied = policy.isSatisfied(evidence, AcrLevel.LOA2, acc)

            then("it still carries loa2") {
                acr shouldBe AcrLevel.LOA2
                satisfied shouldBe true
            }
        }

        `when`("it was proven 31 minutes ago") {
            val evidence = sms.provenAt(TEST_NOW.minus(Duration.ofMinutes(31)))
            val acr = policy.resolveAcr(evidence, acc)
            val loa2 = policy.isSatisfied(evidence, AcrLevel.LOA2, acc)
            val loa1 = policy.isSatisfied(evidence, AcrLevel.LOA1, acc)
            val offered = policy.authCandidates(candidates(evidence, AcrLevel.LOA2, acc))

            then("it carries only loa1") {
                acr shouldBe AcrLevel.LOA1
                loa2 shouldBe false
                loa1 shouldBe true
            }

            then("the same method is offered again to prove it anew") {
                offered shouldContainExactly listOf(ToolId("auth-sms"))
            }
        }

        `when`("its age is unknown") {
            val evidence = SessionEvidence(sms.methods.map { it.copy(provenAt = null) })
            val acr = policy.resolveAcr(evidence, acc)

            then("it carries only loa1") {
                acr shouldBe AcrLevel.LOA1
            }
        }
    }

    given("a synthetic catalog of ident-fsc/enroll-sms/auth-sms plus a hypothetical passkey pair") {

        `when`("evidence proves only sms - a single possession factor, below loa3") {
            val evidence = SessionEvidence.fromNow(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION), methodAcr = mapOf("sms" to AcrLevel.LOA2.value))

            then("isSatisfied only requires the level, not MFA") {
                policy.isSatisfied(evidence, AcrLevel.LOA2, account = null) shouldBe true
                policy.isSatisfied(evidence, AcrLevel.LOA1, account = null) shouldBe true
            }
        }

        `when`("checking isSatisfied at loa3") {
            then("a single factor type is not enough") {
                val singleFactor = SessionEvidence.fromNow(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION), methodAcr = mapOf("sms" to AcrLevel.LOA2.value))
                policy.isSatisfied(singleFactor, AcrLevel.LOA3, account = null) shouldBe false
            }

            then("two distinct factor types proven by one tool are enough") {
                val twoFactors = SessionEvidence.fromNow(amr = listOf("passkey"), factorTypes = setOf(FactorType.POSSESSION, FactorType.INHERENCE), methodAcr = mapOf("passkey" to AcrLevel.LOA3.value))
                policy.isSatisfied(twoFactors, AcrLevel.LOA3, account = null) shouldBe true
            }
        }

        `when`("evidence carries two proofs of the same factor type") {
            then("MFA at loa3 is never satisfied") {
                val evidence = SessionEvidence.fromNow(
                    amr = listOf("sms", "someOtherPossessionMethod"),
                    factorTypes = setOf(FactorType.POSSESSION),
                    methodAcr = mapOf("sms" to AcrLevel.LOA2.value, "someOtherPossessionMethod" to AcrLevel.LOA2.value)
                )
                policy.isSatisfied(evidence, AcrLevel.LOA3, account = null) shouldBe false
            }
        }

        `when`("an account has sms enrolled under loa1") {
            val acc = account(method("sms", enrolledUnderAcr = AcrLevel.LOA1))

            then("reachability respects enrolledUnderAcr, not just the tool's maxAcr") {
                // Single method, single factor type: the "needs another factor type" branch is
                // checked before the enrolledUnderAcr cap, whichever was the actual blocker.
                policy.reachability(acc, AcrLevel.LOA2) shouldBe
                    Reachability.NotReachable(UnreachableReason.SingleFactorType(listOf("sms"), setOf(FactorType.POSSESSION)))
                policy.reachability(acc, AcrLevel.LOA1) shouldBe Reachability.Reachable
            }
        }

        `when`("an account has no active method") {
            then("reachability is NotReachable(NoActiveMethod)") {
                policy.reachability(account(), AcrLevel.LOA1) shouldBe Reachability.NotReachable(UnreachableReason.NoActiveMethod)
            }
        }

        `when`("checking reachability at MFA level (loa3)") {
            then("a single possession-only method is not enough") {
                val onlyPossession = account(method("sms", AcrLevel.LOA2))
                policy.reachability(onlyPossession, AcrLevel.LOA3) shouldBe
                    Reachability.NotReachable(UnreachableReason.SingleFactorType(listOf("sms"), setOf(FactorType.POSSESSION)))
            }

            then("a passkey covering two factor types on its own is enough") {
                val withPasskey = account(method("passkey", AcrLevel.LOA3))
                policy.reachability(withPasskey, AcrLevel.LOA3) shouldBe Reachability.Reachable
            }
        }

        `when`("resolving enrollment candidates") {
            then("already active methods are excluded") {
                val noMethods = account()
                policy.enrollmentCandidates(candidates(SessionEvidence(emptyList()), AcrLevel.LOA2, noMethods)) shouldContainExactlyInAnyOrder listOf(ToolId("enroll-sms"), ToolId("enroll-passkey"))

                val withSms = account(method("sms", AcrLevel.LOA2))
                policy.enrollmentCandidates(candidates(SessionEvidence(emptyList()), AcrLevel.LOA2, withSms)) shouldContainExactly listOf(ToolId("enroll-passkey"))
            }
        }

        `when`("resolving candidate AUTH tools for the current session") {
            val acc = account(method("sms", AcrLevel.LOA2))

            then("methods already used this session are excluded") {
                val fresh = SessionEvidence(emptyList())
                policy.authCandidates(candidates(fresh, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId, availableTools = null)) shouldContainExactly listOf(ToolId("auth-sms"))

                val alreadyUsedSms = SessionEvidence.fromNow(listOf("sms"), setOf(FactorType.POSSESSION), mapOf("sms" to AcrLevel.LOA2.value))
                policy.authCandidates(candidates(alreadyUsedSms, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId, availableTools = null)).shouldBeEmpty()
            }

            then("a null bindingKeyRef (WEB channel, no device) is accepted without crashing") {
                val fresh = SessionEvidence(emptyList())
                policy.authCandidates(candidates(fresh, AcrLevel.LOA2, acc, null, linkedAccountId = null, availableTools = null)) shouldContainExactly listOf(ToolId("auth-sms"))
            }
        }

        `when`("an active method alone reaches the target but this channel can never actually offer it (availableTools)") {
            then("it must not suppress the two-factor combination fallback for the methods that ARE offerable here - regression for a real bug report (CONFIRM_PEER_LOGIN aborting for an account with both sms and email active)") {
                // Mirrors the real catalog: auth-qr reaches loa2 alone, but the App never declares it
                // in availableTools. sms and email are each capped at loa1.
                val tokenSms = descriptor("auth-sms", ToolRole.KNOWN_ACCOUNT_AUTH, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
                val tokenEmail = descriptor("auth-email", ToolRole.KNOWN_ACCOUNT_AUTH, "email", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
                val tokenQr = descriptor("auth-qr", ToolRole.KNOWN_ACCOUNT_AUTH, "qr", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenSms, tokenEmail, tokenQr)), TEST_CLOCK)
                val acc = account(method("sms", AcrLevel.LOA2), method("email", AcrLevel.LOA2), method("qr", AcrLevel.LOA2))
                val fresh = SessionEvidence(emptyList())

                // Whether a single method suffices is decided over the offerable methods only.
                // Otherwise qr would suppress the sms+email combination and leave no candidate.
                localPolicy.authCandidates(
                    candidates(
                        fresh, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId,
                        availableTools = setOf(ToolId("auth-sms"), ToolId("auth-email"))
                    )
                ) shouldContainExactlyInAnyOrder listOf(ToolId("auth-sms"), ToolId("auth-email"))
            }
        }

        `when`("resolving candidate AUTH tools for a key-bound method") {
            val deviceAuth = object : ToolDescriptor {
                override val toolId = ToolId("auth-device")
                override val role = ToolRole.KNOWN_ACCOUNT_AUTH
                override val method = "device"
                override val factorTypes = setOf(FactorType.POSSESSION)
                override val maxAcr = AcrLevel.LOA2
                override val allowsMultipleInstances = true
                override val keyBinding = CallerKeyBinding { instanceDetails, callerBindingKeyRef ->
                    instanceDetails?.get("deviceBindingKeyRef") == callerBindingKeyRef
                }
            }
            val deviceRegistry = ToolHandlerRegistry(listOf(deviceAuth))
            val devicePolicy = DefaultAuthPolicy(deviceRegistry, TEST_CLOCK)
            val deviceMethod = AuthMethodView(
                id = "device-instance", method = "device", active = true, createdAt = null,
                enrolledUnderAcr = AcrLevel.LOA2.value, details = mapOf("deviceBindingKeyRef" to "key-1"),
                enrollmentRef = EnrollmentRef("auth_device.enrollment", "1")
            )
            val acc = account(deviceMethod)
            val fresh = SessionEvidence(emptyList())

            then("it is offered while the device is still linked to this same account") {
                devicePolicy.authCandidates(candidates(fresh, AcrLevel.LOA2, acc, "key-1", linkedAccountId = acc.accountId, availableTools = null)) shouldContainExactly listOf(ToolId("auth-device"))
            }

            then("it is NOT offered once the device has been rebound to a different account") {
                devicePolicy.authCandidates(candidates(fresh, AcrLevel.LOA2, acc, "key-1", linkedAccountId = AccountId(999L), availableTools = null)).shouldBeEmpty()
            }
        }

        `when`("resolving re-identification candidates (reIdentCandidates)") {
            then("an IDENT tool already used this session is excluded, regardless of level") {
                val fresh = SessionEvidence(emptyList())
                policy.reIdentCandidates(candidates(fresh, AcrLevel.LOA2)) shouldContainExactly listOf(ToolId("ident-fsc"))

                val alreadyIdentified = SessionEvidence.fromNow(listOf("fsc"), setOf(FactorType.POSSESSION))
                policy.reIdentCandidates(candidates(alreadyIdentified, AcrLevel.LOA2)).shouldBeEmpty()
            }

            then("an IDENT tool whose own maxAcr falls short of requiredAcr is excluded") {
                val fresh = SessionEvidence(emptyList())
                // ident-fsc tops out at loa2 (see catalog above), so it cannot close a loa3 gap alone.
                policy.reIdentCandidates(candidates(fresh, AcrLevel.LOA3)).shouldBeEmpty()
            }

            then("AUTH/ENROLL tools never appear, only IDENTIFICATION-role ones") {
                policy.reIdentCandidates(candidates(SessionEvidence(emptyList()), AcrLevel.LOA2)) shouldContainExactly listOf(ToolId("ident-fsc"))
            }
        }

        `when`("explaining why an account can't reach a level (reachability's NotReachable reason)") {
            then("no active method at all gives that as the reason") {
                policy.reachability(account(), AcrLevel.LOA2) shouldBe Reachability.NotReachable(UnreachableReason.NoActiveMethod)
            }

            then("active methods sharing one factor type name that as the blocker") {
                // sms is the only active method: the "offered.size <= 1" branch, not the
                // "combinable but capped" one (see the loa1-cap case below).
                val onlySms = account(method("sms", AcrLevel.LOA2))
                policy.reachability(onlySms, AcrLevel.LOA3) shouldBe
                    Reachability.NotReachable(UnreachableReason.SingleFactorType(listOf("sms"), setOf(FactorType.POSSESSION)))
            }

            then("two combinable methods enrolled only under a lower level name the enrolledUnderAcr cap as the blocker") {
                val tokenA = descriptor("auth-a", ToolRole.KNOWN_ACCOUNT_AUTH, "a", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
                val tokenB = descriptor("auth-b", ToolRole.KNOWN_ACCOUNT_AUTH, "b", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)), TEST_CLOCK)
                val bothWeak = account(method("a", enrolledUnderAcr = AcrLevel.LOA1), method("b", enrolledUnderAcr = AcrLevel.LOA1))

                localPolicy.reachability(bothWeak, AcrLevel.LOA2) shouldBe
                    Reachability.NotReachable(UnreachableReason.CombinationCapped(AcrLevel.LOA1))
            }

            then("a single method covering two factor types on its own names the enrolledUnderAcr cap, never 'needs another factor type'") {
                // `passkey` alone covers POSSESSION and INHERENCE, so "needs a different factor
                // type" would contradict itself here.
                val onlyPasskeyWeak = account(method("passkey", enrolledUnderAcr = AcrLevel.LOA1))
                val reachability = policy.reachability(onlyPasskeyWeak, AcrLevel.LOA3)

                reachability shouldBe Reachability.NotReachable(UnreachableReason.SingleMethodCapped("passkey", AcrLevel.LOA1))
            }
        }

        `when`("resolving the achieved ACR from proven amr methods") {
            then("it reflects the highest maxAcr among them") {
                policy.resolveAcr(SessionEvidence(emptyList()), account = null) shouldBe AcrLevel.NONE
                policy.resolveAcr(SessionEvidence.fromNow(listOf("sms"), setOf(FactorType.POSSESSION), mapOf("sms" to AcrLevel.LOA2.value)), account = null) shouldBe AcrLevel.LOA2
                policy.resolveAcr(SessionEvidence.fromNow(listOf("passkey"), setOf(FactorType.POSSESSION, FactorType.INHERENCE), mapOf("passkey" to AcrLevel.LOA3.value)), account = null) shouldBe AcrLevel.LOA3
            }

            then("a single IDENTITY tool covering two factor types on its own (e.g. ident-eid: card+PIN) satisfies MFA on the IDENTITY axis alone") {
                val identEid = descriptor("ident-eid", ToolRole.IDENTIFICATION, "eid", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), AcrLevel.LOA3)
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(identEid)), TEST_CLOCK)
                val evidence = SessionEvidence.fromNow(
                    listOf("eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), mapOf("eid" to AcrLevel.LOA3.value),
                    axis = mapOf("eid" to EvidenceAxis.IDENTITY)
                )
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA3
                localPolicy.isSatisfied(evidence, AcrLevel.LOA3, account = null) shouldBe true
            }

            then("a single ident-fsc reaches loa2 on its own IAL, not via an MFA bump") {
                val identOnly = SessionEvidence.fromNow(
                    listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to AcrLevel.LOA2.value),
                    axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
                )
                policy.resolveAcr(identOnly, account = null) shouldBe AcrLevel.LOA2
            }

            then("an identification must NOT combine with one unrelated AUTH factor into a false MFA bump") {
                // The identification sits on the IDENTITY axis. An attacker who steals the password
                // only has to defeat the password, so fsc + password is one authenticator, not two,
                // and gets no MFA bump even with a claimed loa3 enrolledUnderAcr.
                val tokenPassword = descriptor("auth-password", ToolRole.KNOWN_ACCOUNT_AUTH, "password", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(identFsc, tokenPassword)), TEST_CLOCK)
                val evidence = SessionEvidence.fromNow(
                    amr = listOf("fsc", "password"),
                    factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                    methodAcr = mapOf("fsc" to AcrLevel.LOA2.value, "password" to AcrLevel.LOA1.value),
                    enrolledUnderAcr = mapOf("password" to AcrLevel.LOA3.value),
                    axis = mapOf("fsc" to EvidenceAxis.IDENTITY, "password" to EvidenceAxis.AUTHENTICATOR)
                )
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
                localPolicy.isSatisfied(evidence, AcrLevel.LOA3, account = null) shouldBe false
            }
        }
    }

    given("one method with an enrollment and an auth procedure that declare different factor types (like qr)") {
        val enrollX = descriptor("enroll-x", ToolRole.ENROLLMENT, "x", emptySet(), AcrLevel.LOA1)
        val authX = descriptor("auth-x", ToolRole.KNOWN_ACCOUNT_AUTH, "x", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), AcrLevel.LOA2)
        val account = account(method("x", enrolledUnderAcr = AcrLevel.LOA2))

        `when`("the catalog lists them in either order") {
            val enrollFirst = DefaultAuthPolicy(ToolHandlerRegistry(listOf(enrollX, authX)), TEST_CLOCK).reachability(account, AcrLevel.LOA2)
            val authFirst = DefaultAuthPolicy(ToolHandlerRegistry(listOf(authX, enrollX)), TEST_CLOCK).reachability(account, AcrLevel.LOA2)

            then("reachability counts what proving the method gives, whatever the bean order") {
                enrollFirst shouldBe Reachability.Reachable
                authFirst shouldBe Reachability.Reachable
            }
        }
    }

    given("two loa1-only tools of different factor types (a=possession, b=knowledge), distinct from the shared catalog") {
        val tokenA = descriptor("auth-a", ToolRole.KNOWN_ACCOUNT_AUTH, "a", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
        val tokenB = descriptor("auth-b", ToolRole.KNOWN_ACCOUNT_AUTH, "b", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
        val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)), TEST_CLOCK)
        // enrolledUnderAcr is the caller's claim in SessionEvidence; AuthPolicy does not re-derive it
        // from the account. Each scenario builds the evidence a real caller would resolve.
        fun evidence(enrolledUnderAcr: Map<String, String>) =
            SessionEvidence.fromNow(amr = listOf("a", "b"), factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), methodAcr = mapOf("a" to AcrLevel.LOA1.value, "b" to AcrLevel.LOA1.value), enrolledUnderAcr = enrolledUnderAcr)

        `when`("both were enrolled in a loa1-only session") {
            val bothWeak = account(method("a", enrolledUnderAcr = AcrLevel.LOA1), method("b", enrolledUnderAcr = AcrLevel.LOA1))
            val weakEvidence = evidence(mapOf("a" to AcrLevel.LOA1.value, "b" to AcrLevel.LOA1.value))

            // Nothing vouches for loa2, so the combination stays at loa1: a compromised weak session
            // must not self-escalate by adding a second weak factor (docs/06-ablaeufe.md #1).
            then("the MFA bump is capped at loa1") {
                localPolicy.resolveAcr(weakEvidence, bothWeak) shouldBe AcrLevel.LOA1
                localPolicy.isSatisfied(weakEvidence, AcrLevel.LOA2, bothWeak) shouldBe false
                localPolicy.reachability(bothWeak, AcrLevel.LOA2) shouldBe Reachability.NotReachable(UnreachableReason.CombinationCapped(AcrLevel.LOA1))
            }
        }

        `when`("one of the two was enrolled right after a loa2-level session (e.g. an identification)") {
            val oneVouched = account(method("a", enrolledUnderAcr = AcrLevel.LOA2), method("b", enrolledUnderAcr = AcrLevel.LOA1))
            val vouchedEvidence = evidence(mapOf("a" to AcrLevel.LOA2.value, "b" to AcrLevel.LOA1.value))

            then("the pair reaches loa2 together") {
                localPolicy.resolveAcr(vouchedEvidence, oneVouched) shouldBe AcrLevel.LOA2
                localPolicy.isSatisfied(vouchedEvidence, AcrLevel.LOA2, oneVouched) shouldBe true
                localPolicy.reachability(oneVouched, AcrLevel.LOA2) shouldBe Reachability.Reachable
            }
        }

        `when`("no enrolledUnderAcr claim is given at all") {
            val evidence = evidence(emptyMap())

            then("the bump is conservatively withheld") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA1
            }
        }
    }

    given("loa2 as this project's label for NIST 800-63B AAL2 (docs/04-orchestrierung.md #8)") {
        // AAL2 per NIST: "a multi-factor authenticator, or a combination of two single-factor
        // authenticators". Both paths reach loa2.

        `when`("two single-factor AUTH tools of different kinds combine, with no identification at all") {
            val tokenSms = descriptor("auth-sms", ToolRole.KNOWN_ACCOUNT_AUTH, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
            val tokenPassword = descriptor("auth-password", ToolRole.KNOWN_ACCOUNT_AUTH, "password", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenSms, tokenPassword)), TEST_CLOCK)
            val evidence = SessionEvidence.fromNow(
                amr = listOf("sms", "password"),
                factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                methodAcr = mapOf("sms" to AcrLevel.LOA1.value, "password" to AcrLevel.LOA1.value),
                enrolledUnderAcr = mapOf("sms" to AcrLevel.LOA2.value, "password" to AcrLevel.LOA2.value)
            )

            then("NIST's 'two single-factor authenticators' path reaches loa2 (AAL2) on its own") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
                localPolicy.isSatisfied(evidence, AcrLevel.LOA2, account = null) shouldBe true
            }
        }

        `when`("a single AUTH tool declares two factor types itself (device-like: possession+knowledge)") {
            val tokenDevice = descriptor("auth-device", ToolRole.KNOWN_ACCOUNT_AUTH, "device", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), AcrLevel.LOA2)
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenDevice)), TEST_CLOCK)
            val evidence = SessionEvidence.fromNow(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), mapOf("device" to AcrLevel.LOA2.value))

            then("NIST's 'multi-factor authenticator' path reaches loa2 (AAL2) alone, no combination needed") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
                localPolicy.isSatisfied(evidence, AcrLevel.LOA2, account = null) shouldBe true
            }
        }

        `when`("only an identification tool ran this session, no AUTH factor at all") {
            val evidence = SessionEvidence.fromNow(
                listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to AcrLevel.LOA2.value),
                axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
            )

            then("identification is an equally valid, not a lesser, path to the loa2/AAL2 threshold") {
                policy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
                policy.isSatisfied(evidence, AcrLevel.LOA2, account = null) shouldBe true
            }
        }

        `when`("two ALREADY loa2-rated methods of different factor types combine") {
            // NIST defines a combination rule only for AAL2. AAL3 requires a specific authenticator
            // technology (hardware-based, verifier-impersonation-resistant), so the generic bump
            // stops at loa2.
            val tokenA = descriptor("auth-a", ToolRole.KNOWN_ACCOUNT_AUTH, "a", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
            val tokenB = descriptor("auth-b", ToolRole.KNOWN_ACCOUNT_AUTH, "b", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA2)
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)), TEST_CLOCK)
            val evidence = SessionEvidence.fromNow(
                amr = listOf("a", "b"),
                factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                methodAcr = mapOf("a" to AcrLevel.LOA2.value, "b" to AcrLevel.LOA2.value),
                // Claims loa3 enrolledUnderAcr on both, so only the NIST ceiling holds it at loa2.
                enrolledUnderAcr = mapOf("a" to AcrLevel.LOA3.value, "b" to AcrLevel.LOA3.value)
            )

            then("the result stays at loa2, never bumps on to loa3") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
            }
        }
    }

    given("requiresSatisfied - the generic ToolDescriptor.requires gate") {
        fun profile(vararg established: Pair<AttributeType, ClaimTrust>) = AccountProfile(
            accountId = AccountId(1L), personId = null, authenticationMethods = emptyList(),
            establishedClaims = established.toMap()
        )

        then("an attribute established at the required level satisfies it") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.FAMILY_NAME, ClaimTrust.PROVEN),
                profile(AttributeType.FAMILY_NAME to ClaimTrust.PROVEN)
            ) shouldBe true
        }

        then("a stronger source satisfies a weaker requirement") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.FAMILY_NAME, ClaimTrust.PROVEN),
                profile(AttributeType.FAMILY_NAME to ClaimTrust.AUTHORITATIVE)
            ) shouldBe true
        }

        then("a weaker source does not satisfy a stronger requirement") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.FAMILY_NAME, ClaimTrust.PROVEN),
                profile(AttributeType.FAMILY_NAME to ClaimTrust.SELF_REPORTED)
            ) shouldBe false
        }

        then("an attribute the account never established does not satisfy it") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.BIRTH_DATE, ClaimTrust.PROVEN),
                profile(AttributeType.FAMILY_NAME to ClaimTrust.PROVEN)
            ) shouldBe false
        }

        // A retraction removes the claim from establishedClaims (ADR-12), so a withdrawn value
        // stops satisfying the requirement.
        then("a retracted attribute stops satisfying it") {
            requiresSatisfied(ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN), profile()) shouldBe false
        }

        then("no account at all satisfies nothing") {
            requiresSatisfied(ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN), null) shouldBe false
        }

        // enroll-password's gate as one case of the general rule.
        then("a confirmed email still satisfies ClaimRequirement(EMAIL, PROVEN)") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN),
                profile(AttributeType.EMAIL to ClaimTrust.PROVEN)
            ) shouldBe true
        }
    }
})
