package com.example.identity.tools.ident_eid.internal

import java.time.LocalDate

/** One well-formed card read, shared by the flow and the handler test. */
internal object EidFixtures {

    /** The card fields `input` asks for until the card is read; the PIN is asked for afterwards. */
    val CARD_FIELDS = listOf("familyName", "givenNames", "birthDate", "streetAddress", "postalCode", "locality", "restrictedId")

    val CARD = EidPatchFields(
        familyName = "Muster",
        givenNames = "Max",
        birthDate = LocalDate.of(1970, 1, 1),
        streetAddress = "Musterweg 1",
        postalCode = "12345",
        locality = "Musterstadt",
        restrictedId = "T0103005K1D5S0V8T9W6UM2RTX"
    )
}
