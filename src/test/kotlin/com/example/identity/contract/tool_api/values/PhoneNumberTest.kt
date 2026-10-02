package com.example.identity.contract.tool_api.values

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** One normalization for rate limit, sending and storage. */
class PhoneNumberTest : BehaviorSpec({
    given("one German number in the ways people write it") {
        val spellings = listOf(
            "+49 170 1234567", "+49-170-1234567", "+49/170/1234567", "+49 (170) 123 45-67",
            "0049 170 1234567", "0049 (170) 123-4567", "+49.170.1234567",
        )

        `when`("each spelling is parsed") {
            val parsed = spellings.map { PhoneNumber.parse(it)?.value }

            then("every spelling is the same number") {
                parsed.distinct() shouldBe listOf("+491701234567")
            }
        }
    }

    given("a Norwegian number, from the EEA but outside the EU") {
        `when`("it is parsed") {
            val parsed = PhoneNumber.parse("+47 412 34 567")

            then("it is a number we send to") {
                parsed?.value shouldBe "+4741234567"
            }
        }
    }

    given("numbers we do not send to: no country code, outside the EU/EEA, too short, not a number") {
        val numbers = listOf("0170 1234567", "+1 202 5550123", "+44 7700 900123", "+49 170", "+49 12", "hallo")

        `when`("each is parsed") {
            val parsed = numbers.map { PhoneNumber.parse(it) }

            then("none is a number") {
                parsed.filterNotNull().shouldBeEmpty()
            }
        }
    }
})
