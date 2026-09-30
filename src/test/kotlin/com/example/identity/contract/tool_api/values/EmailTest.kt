package com.example.identity.contract.tool_api.values

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class EmailTest : BehaviorSpec({

    given("parse") {
        then("normalizes to trimmed lowercase") {
            Email.parse("  Max@Example.COM ")?.value shouldBe "max@example.com"
        }
        then("rejects a value with no @ or no domain dot") {
            Email.parse("not-an-email").shouldBeNull()
            Email.parse("max@example").shouldBeNull()
            Email.parse("@example.com").shouldBeNull()
        }
        then("accepts a well-formed address") {
            Email.parse("max@example.com").shouldNotBeNull()
        }
    }

    given("the constructor") {
        then("throws for a malformed address") {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { Email("not-an-email") }
        }
        then("throws for an address not in normal form - normalizing is parse's job") {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { Email("Max@Example.COM") }
        }
        then("takes an address in normal form") {
            Email("max@example.com").value shouldBe "max@example.com"
        }
    }

    given("the length limit (RFC 5321)") {
        then("254 characters is an address, 255 is none") {
            val local = "a".repeat(64)
            val domain254 = "b".repeat(254 - local.length - 1 - 4) + ".com"
            Email.parse("$local@$domain254")!!.value.length shouldBe 254
            Email.parse("$local@b$domain254") shouldBe null
        }
    }
})
