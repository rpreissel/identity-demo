package com.example.identity.contract.tool_api.values

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PartnerNumberTest : BehaviorSpec({

    given("parse") {
        then("normalizes to trimmed uppercase") {
            PartnerNumber.parse(" p000000042 ")?.value shouldBe "P000000042"
        }
        then("rejects a value that isn't P followed by nine digits") {
            PartnerNumber.parse("P00000042").shouldBeNull()
            PartnerNumber.parse("X000000042").shouldBeNull()
            PartnerNumber.parse("xx").shouldBeNull()
        }
    }

    given("the constructor") {
        then("throws for a malformed value") {
            shouldThrow<IllegalArgumentException> { PartnerNumber("xx") }
        }
        then("throws for a value not in normal form - normalizing is parse's job") {
            shouldThrow<IllegalArgumentException> { PartnerNumber("p000000042") }
        }
        then("takes a value in normal form") {
            PartnerNumber("P000000042").value shouldBe "P000000042"
        }
    }
})
