package com.example.identity.tools.ident_eid.internal

import com.example.identity.tools.ident_eid.internal.EidFixtures.CARD
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

private val TODAY = LocalDate.of(2026, 9, 28)

private val PIN = EidPatchFields(pin = IdentEidFlow.MOCK_PIN)

/**
 * The card checks and the merging of PATCHes. What a rejected card or PIN leaves behind, and
 * whether the PIN matches, is covered through the handler by [IdentEidToolHandlerTest].
 */
class IdentEidFlowTest : BehaviorSpec({

    given("a fresh state") {
        val state = IdentEidState()

        `when`("an empty PATCH is decided") {
            val decision = IdentEidFlow.decide(state, EidPatchFields(), TODAY)

            then("it is incomplete") {
                decision shouldBe IdentEidDecision.Incomplete
            }
        }
    }

    given("a fresh state and part of the card data, one field malformed") {
        val partial = EidPatchFields(familyName = "Muster", givenNames = "Max", postalCode = "abc")

        `when`("it is merged and decided") {
            val state = IdentEidFlow.merge(IdentEidState(), partial)
            val decision = IdentEidFlow.decide(state, partial, TODAY)

            then("only the rest is missing") {
                IdentEidFlow.missingFields(state) shouldBe listOf("birthDate", "streetAddress", "locality", "restrictedId")
            }

            then("nothing is checked yet") {
                decision shouldBe IdentEidDecision.Incomplete
            }
        }
    }

    given("complete card data that fails a format check") {
        mapOf(
            "a birth date in the future" to CARD.copy(birthDate = TODAY.plusDays(1)),
            "a restricted id with characters a card never shows" to CARD.copy(restrictedId = "T0103005-K1D5S0V8T9W6"),
            "a restricted id too short for a card pseudonym" to CARD.copy(restrictedId = "T0103005")
        ).forEach { (case, card) ->
            `when`("it carries $case") {
                val decision = IdentEidFlow.decide(IdentEidFlow.merge(IdentEidState(), card), card, TODAY)

                then("the card is rejected before any PIN is asked for") {
                    decision shouldBe IdentEidDecision.CardRejected
                }
            }
        }
    }

    given("checked card data and a PIN") {
        val state = IdentEidFlow.merge(IdentEidFlow.merge(IdentEidState(), CARD), PIN)

        `when`("only the PIN was submitted") {
            val decision = IdentEidFlow.decide(state, PIN, TODAY)

            then("the PIN is to be checked, carrying only what the card showed") {
                val verify = decision.shouldBeInstanceOf<IdentEidDecision.VerifyPin>()
                verify.claimed.familyName shouldBe "Muster"
                verify.claimed.givenNames shouldBe "Max"
                verify.claimed.birthDate shouldBe LocalDate.of(1970, 1, 1)
                verify.restrictedId shouldBe "T0103005K1D5S0V8T9W6UM2RTX"
            }
        }

        `when`("a card field is changed to something malformed") {
            val change = EidPatchFields(postalCode = "ABCDE")
            val decision = IdentEidFlow.decide(IdentEidFlow.merge(state, change), change, TODAY)

            then("the card is checked again and rejected") {
                decision shouldBe IdentEidDecision.CardRejected
            }
        }
    }

    given("a state holding the read card") {
        val withCard = IdentEidFlow.merge(IdentEidState(), CARD)

        `when`("a later PATCH brings only the PIN") {
            val afterPin = IdentEidFlow.merge(withCard, PIN)

            then("it does not overwrite the fields it doesn't mention") {
                afterPin.familyName shouldBe "Muster"
                afterPin.birthDate shouldBe LocalDate.of(1970, 1, 1)
            }
        }
    }

    given("a postal code with surrounding blanks") {
        `when`("it is merged") {
            val merged = IdentEidFlow.merge(IdentEidState(), EidPatchFields(postalCode = " 12345 "))

            then("the blanks are trimmed off what the card shows") {
                merged.postalCode shouldBe "12345"
            }
        }
    }
})
