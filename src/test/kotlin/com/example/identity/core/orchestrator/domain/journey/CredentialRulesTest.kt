package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.CallerKeyBinding
import com.example.identity.contract.tool_api.EnrollmentRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/** The credential rules of the acting phase (docs/adr/ADR-040-fachkern-im-paket-domain.md). */
class CredentialRulesTest : BehaviorSpec({

    fun instance(id: String, enrolledUnder: String?, key: String? = null) =
        AuthMethodView(id, "device", true, null, enrolledUnder, key?.let { mapOf("key" to it) }, EnrollmentRef("device", id))
    val onCallerKey = CallerKeyBinding { details, caller -> details?.get("key") == caller }

    val loa1Key = "key-of-the-loa1-instance"
    val loa2Key = "key-of-the-loa2-instance"
    val unknownKey = "key-of-no-instance"

    // The level a new credential is written under (ADR-5).
    given("a session that has proven nothing yet") {
        `when`("a new credential is written") {
            val level = levelToWriteUnder(AcrLevel.NONE)

            then("it gets the baseline, never the tool's own strength") {
                level shouldBe AcrLevels.DEFAULT_REQUIRED_ACR
            }
        }
    }

    given("a session at loa2") {
        `when`("a new credential is written") {
            val level = levelToWriteUnder(AcrLevel.LOA2)

            then("it gets exactly what the session had proven - no more") {
                level shouldBe AcrLevel.LOA2
            }
        }
    }

    // The level a proof counts at.
    given("two active instances on different keys, enrolled under loa1 and loa2") {
        val active = listOf(instance("a", "loa1", loa1Key), instance("b", "loa2", loa2Key))

        `when`("the proof comes from the loa2 instance's key") {
            val level = proofLevel(active, onCallerKey, loa2Key, AcrLevel.LOA2)

            then("it counts at loa2 - capped by the instance on the caller's key, not by the first active one") {
                level shouldBe AcrLevel.LOA2
            }
        }

        `when`("the proof comes from the loa1 instance's key") {
            val level = proofLevel(active, onCallerKey, loa1Key, AcrLevel.LOA2)

            then("it counts at loa1") {
                level shouldBe AcrLevel.LOA1
            }
        }

        `when`("the proof comes from a key no instance lives on") {
            val level = proofLevel(active, onCallerKey, unknownKey, AcrLevel.LOA2)

            then("the lowest cap applies") {
                level shouldBe AcrLevel.LOA1
            }
        }
    }

    given("two active instances enrolled under loa1 and loa2, a tool without key binding") {
        val active = listOf(instance("a", "loa1"), instance("b", "loa2"))

        `when`("a proof is counted") {
            val level = proofLevel(active, null, null, AcrLevel.LOA2)

            then("it cannot tell which instance was used, so the lowest cap applies") {
                level shouldBe AcrLevel.LOA1
            }
        }
    }

    given("one active instance enrolled under loa2") {
        `when`("the tool itself achieved only loa1") {
            val level = proofLevel(listOf(instance("a", "loa2")), null, null, AcrLevel.LOA1)

            then("the proof counts at loa1 - never above what the tool achieved") {
                level shouldBe AcrLevel.LOA1
            }
        }
    }

    given("no active instance") {
        `when`("a proof is counted") {
            val result = runCatching { proofLevel(emptyList(), null, null, AcrLevel.LOA1) }

            then("it fails - there is nothing to count against") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    // The device link that follows from succeeding.
    given("an intent that binds the device implicitly (FAST_ACCESS)") {
        val intent = AuthIntent.FAST_ACCESS

        `when`("it succeeds on a free device") {
            val links = linksDeviceImplicitly(intent, linkedTo = null, accountId = AccountId(1))

            then("the device is linked") {
                links shouldBe true
            }
        }

        `when`("it succeeds on a device already linked to this account") {
            val links = linksDeviceImplicitly(intent, linkedTo = AccountId(1), accountId = AccountId(1))

            then("the device is linked") {
                links shouldBe true
            }
        }

        `when`("it succeeds on a device linked to another account") {
            val links = linksDeviceImplicitly(intent, linkedTo = AccountId(2), accountId = AccountId(1))

            then("the device is never rebound implicitly") {
                links shouldBe false
            }
        }
    }

    given("LOOKUP_LOGIN, the intent that does not bind devices") {
        `when`("it succeeds on a free device") {
            val links = linksDeviceImplicitly(AuthIntent.LOOKUP_LOGIN, linkedTo = null, accountId = AccountId(1))

            then("the device is not linked") {
                links shouldBe false
            }
        }
    }
})
