package com.example.identity.tools.ident_eid.internal

import java.time.LocalDate

/** One PATCH call's worth of ident-eid input; usually only the current step's fields are non-null. */
data class EidPatchFields(
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    /** Street and house number in one line, as the card's `Street` carries them. */
    val streetAddress: String? = null,
    val postalCode: String? = null,
    val locality: String? = null,
    val restrictedId: String? = null,
    val pin: String? = null
)
