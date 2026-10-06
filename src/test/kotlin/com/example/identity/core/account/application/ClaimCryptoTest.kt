package com.example.identity.core.account.application

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.tools.ident_eid.EID_RESTRICTED_ID
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import com.example.identity.simulation.kms.InMemoryKms
import javax.crypto.AEADBadTagException

/**
 * ADR-52: the key hierarchy of the claim log. What matters is not AES itself but what is bound to
 * what - a key to its account, a value to its batch - and that erasing a batch key erases its values.
 */
class ClaimCryptoTest : BehaviorSpec({

    val alice = AccountId(1L)
    val bob = AccountId(2L)

    given("an account with its master key") {
        val keys = ClaimCryptoFixture()

        `when`("a claim value is written into a batch") {
            val claim = keys.claim(alice, AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY)

            then("the stored bytes do not contain the value, and reading them back gives it") {
                String(claim.encryptedValue!!, Charsets.ISO_8859_1) shouldNotContain "Muster"
                keys.valueOf(claim) shouldBe "Muster"
            }

            then("the digest is the normalized form under the account's key: equal across spellings, different across accounts") {
                val cipher = keys.crypto.open(alice)
                claim.valueDigest shouldBe cipher.digest(AttributeType.FAMILY_NAME, "  MUSTER ")
                claim.valueDigest shouldNotBe cipher.digest(AttributeType.GIVEN_NAMES, "Muster")
                claim.valueDigest!!.length shouldBe 64
                keys.account(bob)
                keys.crypto.open(bob).digest(AttributeType.FAMILY_NAME, "Muster") shouldNotBe claim.valueDigest
            }

            then("a case-sensitive attribute keeps its case in the digest") {
                val cipher = keys.crypto.open(alice)
                cipher.digest(EID_RESTRICTED_ID, "AbC") shouldNotBe cipher.digest(EID_RESTRICTED_ID, "abc")
            }
        }

        `when`("the batch key is deleted") {
            val claim = keys.claim(alice, AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY)
            keys.crypto.deleteBatch(claim.claimBatchId!!)

            then("the value is gone although the row is still there") {
                keys.valueOf(claim).shouldBeNull()
                claim.encryptedValue shouldNotBe null
            }
        }

        `when`("a batch key is presented under another account") {
            val claim = keys.claim(alice, AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY)
            keys.batchKeys.single { it.claimBatchId == claim.claimBatchId }.accountId = keys.account(bob).let { bob }
            val result = runCatching { keys.crypto.open(bob).decrypt(claim) }

            then("it does not open - the wrapping is bound to the account") {
                shouldThrow<AEADBadTagException> { result.getOrThrow() }
            }
        }
    }

    given("a master key wrapped by the KMS") {
        val kms = InMemoryKms()
        val wrapper = KmsKekWrapper(kms.transit)
        val wrapped = wrapper.wrap(AesGcm.newKey())

        `when`("the KEK is rotated in the KMS") {
            kms.transit.rotate(KmsKekWrapper.KEK_KEY)
            val fresh = wrapper.wrap(AesGcm.newKey())

            then("old wraps still open, new ones carry the new version, and both versions are known") {
                wrapped.kekVersion shouldBe "v1"
                fresh.kekVersion shouldBe "v2"
                wrapper.unwrap(wrapped).size shouldBe 32
                wrapper.unwrap(fresh).size shouldBe 32
                wrapper.knownVersions shouldBe setOf("v1", "v2")
            }
        }

        `when`("the old version is retired in the KMS") {
            kms.transit.retireBelow(KmsKekWrapper.KEK_KEY, 2)
            val result = runCatching { wrapper.unwrap(wrapped) }

            then("a key wrapped under it opens no more, and the version is no longer known") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message!! shouldContain "retired"
                wrapper.knownVersions shouldBe setOf("v2")
            }
        }

        `when`("another KMS with its own KEK is asked") {
            val other = KmsKekWrapper(InMemoryKms().transit)
            val result = runCatching { other.unwrap(wrapped) }

            then("it does not open") {
                shouldThrow<AEADBadTagException> { result.getOrThrow() }
            }
        }

        then("the simulated KMS says so, for ProductionModeCheck") {
            wrapper.simulated shouldBe true
        }
    }

    given("retention rules") {
        then("an anchor attribute cannot expire") {
            shouldThrow<IllegalStateException> {
                ClaimRetentionPolicy(ClaimRetentionProperties(mapOf("email" to java.time.Duration.ofDays(1))))
            }.message!! shouldContain "anchor"
        }

        then("an unknown attribute is refused") {
            shouldThrow<IllegalStateException> {
                ClaimRetentionPolicy(ClaimRetentionProperties(mapOf("shoe_size" to java.time.Duration.ofDays(1))))
            }.message!! shouldContain "shoe_size"
        }

        then("a configured attribute expires, an unconfigured one does not") {
            val policy = ClaimRetentionPolicy(ClaimRetentionProperties(mapOf("family_name" to java.time.Duration.ofDays(365))))
            policy.retentionOf(AttributeType.FAMILY_NAME) shouldBe java.time.Duration.ofDays(365)
            policy.retentionOf(AttributeType.GIVEN_NAMES).shouldBeNull()
        }
    }

    given("the fixture's clock") {
        then("claims carry the test instant") {
            ClaimCryptoFixture().claim(alice, AttributeType.FAMILY_NAME, "x", ClaimSource.SELF_REPORTED).establishedAt shouldBe TEST_NOW
        }
    }
})
