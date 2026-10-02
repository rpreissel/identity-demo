package com.example.identity.contract.tool_api.values

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class EmailTest : BehaviorSpec({

    given("an address with spaces and capitals") {
        `when`("it is parsed") {
            val parsed = Email.parse("  Max@Example.COM ")

            then("it is normalized to trimmed lowercase") {
                parsed?.value shouldBe "max@example.com"
            }
        }
    }

    given("values with no @, no domain dot or no local part") {
        val values = listOf("not-an-email", "max@example", "@example.com")

        `when`("each is parsed") {
            val parsed = values.map { Email.parse(it) }

            then("none is an address") {
                parsed.filterNotNull().shouldBeEmpty()
            }
        }
    }

    given("a malformed address") {
        `when`("it is constructed directly") {
            val result = runCatching { Email("not-an-email") }

            then("it throws") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
            }
        }
    }

    given("an address not in normal form") {
        `when`("it is constructed directly") {
            val result = runCatching { Email("Max@Example.COM") }

            then("it throws - normalizing is parse's job") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
            }
        }
    }

    given("an address in normal form") {
        `when`("it is constructed directly") {
            val email = Email("max@example.com")

            then("it takes the address") {
                email.value shouldBe "max@example.com"
            }
        }
    }

    given("addresses at the length limit (RFC 5321)") {
        val local = "a".repeat(64)
        val domain254 = "b".repeat(254 - local.length - 1 - 4) + ".com"

        `when`("one of 254 and one of 255 characters are parsed") {
            val longest = Email.parse("$local@$domain254")
            val tooLong = Email.parse("$local@b$domain254")

            then("254 characters is an address, 255 is none") {
                longest?.value?.length shouldBe 254
                tooLong shouldBe null
            }
        }
    }
})
