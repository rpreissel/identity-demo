package com.example.identity.contract.tool_api.values

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class KvnrTest : BehaviorSpec({

    given("parse") {
        then("normalizes to trimmed uppercase") {
            Kvnr.parse(" a123456789 ")?.value shouldBe "A123456789"
        }
        then("rejects a value that isn't one letter followed by nine digits") {
            Kvnr.parse("A12345678").shouldBeNull()
            Kvnr.parse("A1234567890").shouldBeNull()
            Kvnr.parse("AB123456789").shouldBeNull()
            Kvnr.parse("1234567890").shouldBeNull()
        }
    }

    given("the constructor") {
        then("throws for a malformed value") {
            shouldThrow<IllegalArgumentException> { Kvnr("not-a-kvnr") }
        }
        then("throws for a value not in normal form - normalizing is parse's job") {
            shouldThrow<IllegalArgumentException> { Kvnr("a123456789") }
        }
        then("takes a value in normal form") {
            Kvnr("A123456789").value shouldBe "A123456789"
        }
    }
})
