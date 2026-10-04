package com.example.identity.tools.auth_qr.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

/**
 * The pairing code a human copies by hand (docs/verfahren/qr.md): 8 characters from a
 * Crockford-like alphabet without `I`, `L`, `O` and `U`.
 */
class PairingCodeGeneratorTest : BehaviorSpec({

    given("the pairing code generator") {
        `when`("it draws many pairing codes") {
            val codes = List(2_000) { PairingCodeGenerator.pairingCode() }
            val characters = codes.flatMap { it.toList() }.toSet()

            then("each has exactly 8 characters from digits and upper-case letters") {
                codes.forEach { it shouldMatch Regex("[0-9A-Z]{8}") }
            }

            then("none contains I, L, O or U") {
                characters shouldNotContainAnyOf listOf('I', 'L', 'O', 'U')
            }

            then("the alphabet is the remaining 32 characters, about 40 bits per code") {
                characters shouldContainAll "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toList()
                characters.size shouldBe 32
            }
        }
    }
})
