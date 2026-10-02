package com.example.identity.contract.tool_api.values

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class KvnrTest : BehaviorSpec({

    given("a KVNR in lowercase with spaces") {
        `when`("it is parsed") {
            val parsed = Kvnr.parse(" a123456789 ")

            then("it is normalized to trimmed uppercase") {
                parsed?.value shouldBe "A123456789"
            }
        }
    }

    given("values that aren't one letter followed by nine digits") {
        val values = listOf("A12345678", "A1234567890", "AB123456789", "1234567890")

        `when`("each is parsed") {
            val parsed = values.map { Kvnr.parse(it) }

            then("none is a KVNR") {
                parsed.filterNotNull().shouldBeEmpty()
            }
        }
    }

    given("a malformed value") {
        `when`("it is constructed directly") {
            val result = runCatching { Kvnr("not-a-kvnr") }

            then("it throws") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
            }
        }
    }

    given("a value not in normal form") {
        `when`("it is constructed directly") {
            val result = runCatching { Kvnr("a123456789") }

            then("it throws - normalizing is parse's job") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
            }
        }
    }

    given("a value in normal form") {
        `when`("it is constructed directly") {
            val kvnr = Kvnr("A123456789")

            then("it takes the value") {
                kvnr.value shouldBe "A123456789"
            }
        }
    }
})
