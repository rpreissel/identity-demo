package com.example.identity.tools.ident_eid.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import com.example.identity.contract.tool_api.MissingFields

private val TODAY = LocalDate.of(2026, 9, 28)

private val CARD_FIELDS = listOf("familyName", "givenNames", "birthDate", "streetAddress", "postalCode", "locality", "restrictedId")

private val CARD = EidPatchFields(
    familyName = "Muster",
    givenNames = "Max",
    birthDate = LocalDate.of(1990, 1, 1),
    streetAddress = "Musterstr. 1",
    postalCode = "12345",
    locality = "Musterstadt",
    restrictedId = "T0103005K1D5S0V8T9W6UM2RTX"
)

private val PIN = EidPatchFields(pin = IdentEidFlow.MOCK_PIN)

class IdentEidFlowTest : BehaviorSpec({

    given("a fresh state") {
        val state = IdentEidState()

        then("it names the one step input and asks for the card data only - the PIN is staged, not requested yet") {
            IdentEidFlow.describe(state) shouldBe ("input" to MissingFields(CARD_FIELDS))
        }

        then("decide() reports it as incomplete") {
            IdentEidFlow.decide(state, EidPatchFields(), TODAY) shouldBe IdentEidDecision.Incomplete
        }
    }

    given("part of the card data") {
        val partial = EidPatchFields(familyName = "Muster", givenNames = "Max", postalCode = "abc")
        val state = IdentEidFlow.merge(IdentEidState(), partial)

        then("only the rest is missing, and nothing is checked yet") {
            IdentEidFlow.missingFields(state) shouldBe listOf("birthDate", "streetAddress", "locality", "restrictedId")
            IdentEidFlow.decide(state, partial, TODAY) shouldBe IdentEidDecision.Incomplete
        }
    }

    given("complete, well-formed card data without a PIN") {
        val state = IdentEidFlow.merge(IdentEidState(), CARD)

        then("the PIN is what is still missing, still in step input") {
            IdentEidFlow.describe(state) shouldBe ("input" to MissingFields(listOf("pin")))
        }

        then("decide() still reports it as incomplete") {
            IdentEidFlow.decide(state, CARD, TODAY) shouldBe IdentEidDecision.Incomplete
        }
    }

    given("complete card data that fails a format check") {
        mapOf(
            "a birth date in the future" to CARD.copy(birthDate = TODAY.plusDays(1)),
            "a postal code that is not five digits" to CARD.copy(postalCode = "1234"),
            "a restricted id with characters a card never shows" to CARD.copy(restrictedId = "T0103005-K1D5S0V8T9W6"),
            "a restricted id too short for a card pseudonym" to CARD.copy(restrictedId = "T0103005")
        ).forEach { (case, card) ->
            `when`("it carries $case") {
                val decision = IdentEidFlow.decide(IdentEidFlow.merge(IdentEidState(), card), card, TODAY)

                then("decide() rejects the card before any PIN is asked for") {
                    decision shouldBe IdentEidDecision.CardRejected
                }
            }
        }
    }

    given("checked card data and a PIN") {
        val state = IdentEidFlow.merge(IdentEidFlow.merge(IdentEidState(), CARD), PIN)

        `when`("only the PIN was submitted") {
            val decision = IdentEidFlow.decide(state, PIN, TODAY)

            then("decide() checks the PIN, carrying only what the card showed") {
                decision.shouldBeInstanceOf<IdentEidDecision.VerifyPin>()
                decision.claimed.familyName shouldBe "Muster"
                decision.claimed.givenNames shouldBe "Max"
                decision.claimed.birthDate shouldBe LocalDate.of(1990, 1, 1)
                decision.restrictedId shouldBe "T0103005K1D5S0V8T9W6UM2RTX"
            }
        }

        `when`("a card field is changed to something malformed") {
            val change = EidPatchFields(postalCode = "ABCDE")
            val decision = IdentEidFlow.decide(IdentEidFlow.merge(state, change), change, TODAY)

            then("decide() checks the card again and rejects it") {
                decision shouldBe IdentEidDecision.CardRejected
            }
        }
    }

    given("rejectCard()") {
        then("drops the card data and the PIN - all card fields are missing again") {
            IdentEidFlow.missingFields(IdentEidFlow.rejectCard()) shouldBe CARD_FIELDS
        }
    }

    given("rejectPin()") {
        then("drops only the PIN - the checked card data stays") {
            val state = IdentEidFlow.merge(IdentEidFlow.merge(IdentEidState(), CARD), EidPatchFields(pin = "000000"))
            val rejected = IdentEidFlow.rejectPin(state)

            IdentEidFlow.missingFields(rejected) shouldBe listOf("pin")
            rejected.familyName shouldBe "Muster"
        }
    }

    given("pinMatchesMock()") {
        then("the correct mock PIN matches") {
            val state = IdentEidFlow.merge(IdentEidState(), PIN)
            IdentEidFlow.pinMatchesMock(state.pinHash!!) shouldBe true
        }

        then("a wrong PIN does not match") {
            val state = IdentEidFlow.merge(IdentEidState(), EidPatchFields(pin = "000000"))
            IdentEidFlow.pinMatchesMock(state.pinHash!!) shouldBe false
        }
    }

    given("merge()") {
        then("a later PATCH does not overwrite fields it doesn't mention") {
            val afterPin = IdentEidFlow.merge(IdentEidFlow.merge(IdentEidState(), CARD), PIN)
            afterPin.familyName shouldBe "Muster"
            afterPin.birthDate shouldBe LocalDate.of(1990, 1, 1)
        }

        then("surrounding blanks are trimmed off what the card shows") {
            IdentEidFlow.merge(IdentEidState(), EidPatchFields(postalCode = " 12345 ")).postalCode shouldBe "12345"
        }
    }
})
