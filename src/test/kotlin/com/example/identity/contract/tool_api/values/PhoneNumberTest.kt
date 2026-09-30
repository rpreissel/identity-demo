package com.example.identity.contract.tool_api.values

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** One normalization for rate limit, sending and storage. */
class PhoneNumberTest : BehaviorSpec({
    given("one number in the ways people write it") {
        then("every spelling is the same number") {
            listOf("+49 170 1234567", "+49-170-1234567", "+49/170/1234567", "+49 (170) 123 45-67", "0049 170 1234567", "+49.170.1234567")
                .map { PhoneNumber.of(it).value }.distinct() shouldBe listOf("+491701234567")
        }
    }

    given("numbers we do not send to") {
        then("they are none - no country code, outside the EU/EEA, too short, not a number") {
            listOf("0170 1234567", "+1 202 5550123", "+49 170", "hallo").forEach { PhoneNumber.ofOrNull(it).shouldBeNull() }
        }
    }
})
