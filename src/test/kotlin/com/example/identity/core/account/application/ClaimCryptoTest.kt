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
import java.io.File
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

    given("a wrapped master key") {
        val wrapper = ConfiguredKekWrapper("kek-one-of-at-least-32-characters-long", "1", PreviousMasterKeks())
        val wrapped = wrapper.wrap(AesGcm.newKey())

        `when`("it is unwrapped under another KEK of the same version") {
            val other = ConfiguredKekWrapper("kek-two-of-at-least-32-characters-long", "1", PreviousMasterKeks())
            val result = runCatching { other.unwrap(wrapped) }

            then("it does not open") {
                shouldThrow<AEADBadTagException> { result.getOrThrow() }
            }
        }

        `when`("the KEK was rotated and the old one is listed as previous") {
            val rotated = ConfiguredKekWrapper("kek-two-of-at-least-32-characters-long", "2", PreviousMasterKeks(mapOf("1" to "kek-one-of-at-least-32-characters-long")))

            then("keys of the old version still open, new ones are wrapped under the new version") {
                rotated.unwrap(wrapped) shouldBe wrapper.unwrap(wrapped)
                rotated.wrap(AesGcm.newKey()).kekVersion shouldBe "2"
            }
        }

        `when`("the KEK was rotated and the old one is missing") {
            val rotated = ConfiguredKekWrapper("kek-two-of-at-least-32-characters-long", "2", PreviousMasterKeks())
            val result = runCatching { rotated.unwrap(wrapped) }

            then("the failure names the missing version") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message!! shouldContain "version '1'"
            }
        }
    }

    given("the KEK application.yml ships for the demo") {
        val shipped = File("src/main/resources/application.yml").readText()

        then("application.yml ships ConfiguredKekWrapper's demo value") {
            shipped shouldContain "MASTER_KEK:${ConfiguredKekWrapper.DEMO_KEK}}"
        }

        then("the wrapper recognizes that value, and no other, as the demo KEK") {
            ConfiguredKekWrapper(ConfiguredKekWrapper.DEMO_KEK, "1", PreviousMasterKeks()).usesDemoKek shouldBe true
            ConfiguredKekWrapper("kek-one-of-at-least-32-characters-long", "1", PreviousMasterKeks()).usesDemoKek shouldBe false
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
