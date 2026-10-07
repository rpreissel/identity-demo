package com.example.identity.core.account.application

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.tools.ident_eid.EID_RESTRICTED_ID
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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

            then("it does not open - the wrapping is bound to the key it was made under") {
                shouldThrow<AEADBadTagException> { result.getOrThrow() }
            }
        }
    }

    given("a master key wrapped by the KMS") {
        val kms = InMemoryKms()
        val wrapper = KmsKekWrapper(kms.transit)
        val wrapped = wrapper.wrap("account-master-key", AesGcm.newKey())

        kms.transit.rotate(KmsKekWrapper.KEK_KEY)
        val fresh = wrapper.wrap("account-master-key", AesGcm.newKey())

        `when`("the KEK is rotated in the KMS") {

            then("old wraps still open, new ones carry the new version, and both versions are known") {
                wrapped.kekVersion shouldBe "v1"
                fresh.kekVersion shouldBe "v2"
                wrapper.unwrap("account-master-key", wrapped).size shouldBe 32
                wrapper.unwrap("account-master-key", fresh).size shouldBe 32
                wrapper.knownVersions shouldBe setOf("v1", "v2")
            }
        }

        `when`("the old version is retired in the KMS") {
            kms.transit.retireBelow(KmsKekWrapper.KEK_KEY, 2)
            val result = runCatching { wrapper.unwrap("account-master-key", wrapped) }

            then("a key wrapped under it opens no more, and the version is no longer known") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message!! shouldContain "retired"
                wrapper.knownVersions shouldBe setOf("v2")
            }
        }

        `when`("another KMS with its own KEK is asked") {
            val other = KmsKekWrapper(InMemoryKms().transit)
            val result = runCatching { other.unwrap("account-master-key", wrapped) }

            then("it does not open") {
                shouldThrow<AEADBadTagException> { result.getOrThrow() }
            }
        }

        `when`("it is presented as a data key of another module") {
            val result = runCatching { wrapper.unwrap("orchestrator-data-key", fresh) }

            then("it does not open - the purpose is bound into the wrapping") {
                shouldThrow<AEADBadTagException> { result.getOrThrow() }
            }
        }

        `when`("a row from before the key service names its version without the prefix") {
            val result = runCatching { wrapper.unwrap("account-master-key", WrappedKey(wrapped.bytes, "1")) }

            then("the failure says what the row is, instead of a bare decryption error") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message!! shouldContain "ADR-54"
            }
        }

        then("the simulated KMS says so, for ProductionModeCheck") {
            wrapper.simulated shouldBe true
        }
    }

    given("the per-instance cache of unwrapped master keys") {
        val keys = ClaimCryptoFixture()
        var unwraps = 0
        val counting = object : MasterKeyWrapper by keys.wrapper {
            override fun unwrap(purpose: String, wrapped: WrappedKey): ByteArray = keys.wrapper.unwrap(purpose, wrapped).also { unwraps++ }
        }
        val clock = io.mockk.mockk<java.time.Clock>()
        var now = TEST_NOW
        io.mockk.every { clock.instant() } answers { now }
        val crypto = ClaimCrypto(keys.masterKeyRepository, keys.batchKeyRepository, counting, keys.envelopes, clock, cacheTtl = java.time.Duration.ofMinutes(5))
        keys.account(alice)

        `when`("an account is opened three times within the period, then once after it") {
            repeat(3) { crypto.open(alice) }
            val within = unwraps
            now = TEST_NOW + java.time.Duration.ofMinutes(6)
            crypto.open(alice)

            then("the KMS unwraps once per period") {
                within shouldBe 1
                unwraps shouldBe 2
            }
        }

        `when`("the account is deleted") {
            crypto.open(alice)
            val before = unwraps
            crypto.forget(com.example.identity.core.account.AccountDeleted(alice, listOf(keys.crypto.primaryKeyOf(alice))))
            crypto.open(alice)

            then("the next read unwraps again - the key did not outlive the account in memory") {
                unwraps shouldBe before + 1
            }
        }
    }

    given("a sealed value outside the demo") {
        val keys = ClaimCryptoFixture()
        val claim = keys.claim(alice, AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY)

        then("the row is bare ciphertext without any header") {
            claim.encryptedValue!![0] shouldNotBe '['.code.toByte()
            keys.envelopes.describe(claim.encryptedValue!!).shouldBeNull()
        }

        `when`("a value is presented under another key") {
            val other = keys.claim(alice, AttributeType.GIVEN_NAMES, "Max", ClaimSource.PERSON_DIRECTORY)
            val swapped = AccountClaim(accountId = alice, attributeType = other.attributeType, encryptedValue = claim.encryptedValue, valueDigest = other.valueDigest, claimBatchId = other.claimBatchId, claimSource = other.claimSource, establishedAt = TEST_NOW)

            then("the bound key reference rejects it") {
                shouldThrow<AEADBadTagException> { keys.valueOf(swapped) }
            }
        }
    }

    given("the demo, which stores values readable but says what would seal them") {
        val plain = ClaimCryptoFixture(encryptionEnabled = false)
        val claim = plain.claim(alice, AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY)
        val batch = claim.claimBatchId.toString().take(8)
        val key = plain.masterKeys.single().keyUuid.toString().take(8)

        then("the value sits readable behind the short header naming its batch key and a tag under it, and reads back") {
            val stored = String(claim.encryptedValue!!, Charsets.ISO_8859_1)
            stored shouldMatch Regex("\\[gruppe $batch [0-9a-f]{8}]Muster")
            plain.envelopes.describe(claim.encryptedValue!!) shouldBe stored.substringBefore(']') + "]"
            plain.valueOf(claim) shouldBe "Muster"
        }

        then("a readable value still needs its key - a tampered value or another key fails like a wrong key does") {
            val tampered = claim.encryptedValue!!.copyOf().also { it[it.size - 1] = 'X'.code.toByte() }
            shouldThrow<AEADBadTagException> { plain.valueOf(AccountClaim(accountId = alice, attributeType = claim.attributeType, encryptedValue = tampered, valueDigest = claim.valueDigest, claimBatchId = claim.claimBatchId, claimSource = claim.claimSource, establishedAt = TEST_NOW)) }
        }

        then("the digest is the normalized value behind the account key's header, so dedup still works") {
            claim.valueDigest shouldBe "[konto $key]family_name=muster"
            claim.valueDigest shouldBe plain.crypto.open(alice).digest(AttributeType.FAMILY_NAME, " MUSTER ")
        }

        then("the batch key is wrapped regardless, and says so") {
            String(plain.batchKeys.single().wrappedDek!!, Charsets.ISO_8859_1) shouldStartWith "[konto $key aes]"
        }

        `when`("a value is presented under another key") {
            val other = plain.claim(alice, AttributeType.GIVEN_NAMES, "Max", ClaimSource.PERSON_DIRECTORY)
            val swapped = AccountClaim(accountId = alice, attributeType = other.attributeType, encryptedValue = claim.encryptedValue, valueDigest = other.valueDigest, claimBatchId = other.claimBatchId, claimSource = other.claimSource, establishedAt = TEST_NOW)

            then("the header gives it away") {
                shouldThrow<IllegalStateException> { plain.valueOf(swapped) }.message!! shouldContain "sealed under 'gruppe $batch'"
            }
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
