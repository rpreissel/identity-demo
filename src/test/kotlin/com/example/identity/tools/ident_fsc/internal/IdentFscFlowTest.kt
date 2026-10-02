package com.example.identity.tools.ident_fsc.internal

import com.example.identity.contract.tool_api.values.PartnerNumber
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/** Stands in for the register's digest (ActivationCodes.digest) - the flow only stages what it gets. */
private val DIGEST: (String) -> String = { "digest:$it" }

private val BIRTHDATE = LocalDate.of(1985, 6, 15)

private val PERSONAL_DETAILS = IdentFscInput(kvnr = "A123456789", familyName = "Muster", givenNames = "Max", birthDate = BIRTHDATE, personId = PartnerNumber("P000000005"))

private val ALL_PERSONAL_FIELDS = listOf("kvnr", "familyName", "givenNames", "birthDate")

class IdentFscFlowTest : BehaviorSpec({

    given("a fresh state") {
        val state = IdentFscState()

        `when`("an empty PATCH is decided") {
            val decision = IdentFscFlow.decide(state, IdentFscInput())

            then("it is incomplete") {
                decision shouldBe IdentFscDecision.Incomplete
            }

            then("only the personal data is missing - fsc is staged, not requested yet") {
                IdentFscFlow.missingFields(state) shouldBe ALL_PERSONAL_FIELDS
            }
        }

        `when`("the personal data is submitted, but the KVNR resolved no person") {
            val input = PERSONAL_DETAILS.copy(personId = null)
            val decision = IdentFscFlow.decide(IdentFscFlow.merge(state, input, DIGEST), input)

            then("the person is not found - before any code is asked for") {
                decision shouldBe IdentFscDecision.PersonNotFound
            }
        }

        `when`("the personal data is submitted and resolved a person") {
            val merged = IdentFscFlow.merge(state, PERSONAL_DETAILS, DIGEST)
            val decision = IdentFscFlow.decide(merged, PERSONAL_DETAILS)

            then("the personal data is to be checked right away") {
                decision shouldBe IdentFscDecision.VerifyPersonalDetails(PartnerNumber("P000000005"), "Muster", "Max", BIRTHDATE)
            }

            then("fsc is what is still missing") {
                IdentFscFlow.missingFields(merged) shouldBe listOf("fsc")
            }
        }
    }

    given("verified personal data") {
        val state = IdentFscFlow.merge(IdentFscState(), PERSONAL_DETAILS, DIGEST)

        `when`("only the code is submitted") {
            val input = IdentFscInput(fsc = "VALIDCODE")
            val decision = IdentFscFlow.decide(IdentFscFlow.merge(state, input, DIGEST), input)

            then("only the code is to be checked, as its digest") {
                decision shouldBe IdentFscDecision.VerifyCode(PartnerNumber("P000000005"), "digest:VALIDCODE")
            }
        }

        `when`("a personal field is corrected") {
            val input = IdentFscInput(birthDate = BIRTHDATE.plusDays(1))
            val decision = IdentFscFlow.decide(IdentFscFlow.merge(state, input, DIGEST), input)

            then("the personal data is to be checked again") {
                decision shouldBe IdentFscDecision.VerifyPersonalDetails(PartnerNumber("P000000005"), "Muster", "Max", BIRTHDATE.plusDays(1))
            }
        }

        `when`("a submitted code is rejected") {
            val rejected = IdentFscFlow.rejectCode(IdentFscFlow.merge(state, IdentFscInput(fsc = "WRONGCODE"), DIGEST))

            then("only fsc is asked for again") {
                IdentFscFlow.missingFields(rejected) shouldBe listOf("fsc")
            }
        }

        `when`("the personal data is rejected") {
            val rejected = IdentFscFlow.rejectPersonalDetails()

            then("all of it is asked for again") {
                IdentFscFlow.missingFields(rejected) shouldBe ALL_PERSONAL_FIELDS
            }
        }
    }

    given("personal data identified by KVNR (ADR-34)") {
        val withKvnr = IdentFscFlow.merge(IdentFscState(), PERSONAL_DETAILS, DIGEST)

        `when`("a Partnernummer follows") {
            val merged = IdentFscFlow.merge(withKvnr, IdentFscInput(partnerNumber = "P000000004", personId = PartnerNumber("P000000004")), DIGEST)

            then("it replaces the KVNR, and its person with it") {
                merged.kvnr shouldBe null
                merged.partnerNumber shouldBe "P000000004"
                merged.personId shouldBe PartnerNumber("P000000004")
            }
        }
    }

    given("a state identified by Partnernummer (ADR-34)") {
        val partner = IdentFscFlow.merge(IdentFscState(), IdentFscInput(partnerNumber = "P000000004", personId = PartnerNumber("P000000004")), DIGEST)

        `when`("a KVNR follows") {
            val merged = IdentFscFlow.merge(partner, IdentFscInput(kvnr = "A123456789", personId = PartnerNumber("P000000005")), DIGEST)

            then("it replaces the Partnernummer") {
                merged.kvnr shouldBe "A123456789"
                merged.partnerNumber shouldBe null
                merged.personId shouldBe PartnerNumber("P000000005")
            }
        }

        then("kvnr is not reported missing, only the rest of the personal data") {
            IdentFscFlow.missingFields(partner) shouldBe listOf("familyName", "givenNames", "birthDate")
        }
    }

    given("a fresh state, and KVNR and Partnernummer brought together") {
        val input = IdentFscInput(kvnr = "A123456789", partnerNumber = "P000000004", personId = PartnerNumber("P000000005"))

        `when`("they are merged") {
            val merged = IdentFscFlow.merge(IdentFscState(), input, DIGEST)

            then("the KVNR wins") {
                merged.kvnr shouldBe "A123456789"
                merged.partnerNumber shouldBe null
            }
        }
    }

    given("a fresh state, and the complete personal data of a Partner") {
        val input = IdentFscInput(partnerNumber = "P000000004", familyName = "Schulz", givenNames = "Paula", birthDate = BIRTHDATE, personId = PartnerNumber("P000000004"))

        `when`("it is merged") {
            val merged = IdentFscFlow.merge(IdentFscState(), input, DIGEST)

            then("only fsc is missing - no KVNR is asked for") {
                IdentFscFlow.missingFields(merged) shouldBe listOf("fsc")
            }
        }
    }

    given("a state with KVNR, family name and the resolved person") {
        val state = IdentFscState(kvnr = "A123456789", familyName = "Muster", personId = PartnerNumber("P000000005"))

        `when`("a later PATCH adds givenNames and never touches kvnr/name") {
            val merged = IdentFscFlow.merge(state, IdentFscInput(givenNames = "Max"), DIGEST)

            then("kvnr/name and the person survive, givenNames is added") {
                merged shouldBe state.copy(givenNames = "Max")
            }
        }

        `when`("a later PATCH sends a KVNR that resolves no one") {
            val merged = IdentFscFlow.merge(state, IdentFscInput(kvnr = "Z999999999", personId = null), DIGEST)

            then("the previous KVNR's person does not survive") {
                merged.personId shouldBe null
            }
        }

        `when`("fsc is submitted") {
            val merged = IdentFscFlow.merge(state, IdentFscInput(fsc = "VALIDCODE"), DIGEST)

            then("only its digest is staged, never the code in the clear") {
                merged.fscHash shouldBe "digest:VALIDCODE"
            }
        }
    }
})
