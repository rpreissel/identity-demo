package com.example.identity.contract.tool_api.values

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class PartnerNumberTest : BehaviorSpec({

    given("a Partnernummer in lowercase with spaces") {
        `when`("it is parsed") {
            val parsed = PartnerNumber.parse(" p000000042 ")

            then("it is normalized to trimmed uppercase") {
                parsed?.value shouldBe "P000000042"
            }
        }
    }

    given("values that aren't P followed by nine digits") {
        val values = listOf("P00000042", "X000000042", "xx")

        `when`("each is parsed") {
            val parsed = values.map { PartnerNumber.parse(it) }

            then("none is a Partnernummer") {
                parsed.filterNotNull().shouldBeEmpty()
            }
        }
    }

    given("a malformed value") {
        `when`("it is constructed directly") {
            val result = runCatching { PartnerNumber("xx") }

            then("it throws") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
            }
        }
    }

    given("a value not in normal form") {
        `when`("it is constructed directly") {
            val result = runCatching { PartnerNumber("p000000042") }

            then("it throws - normalizing is parse's job") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
            }
        }
    }

    given("a value in normal form") {
        `when`("it is constructed directly") {
            val partnerNumber = PartnerNumber("P000000042")

            then("it takes the value") {
                partnerNumber.value shouldBe "P000000042"
            }
        }
    }
})
