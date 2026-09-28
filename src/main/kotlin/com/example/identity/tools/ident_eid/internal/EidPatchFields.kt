package com.example.identity.tools.ident_eid.internal

import java.time.LocalDate

/** One PATCH call's worth of ident-eid input; usually only the fields of one page are non-null. */
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
) {
    /** Whether this PATCH touched the card data - which is then checked again, right away. */
    val touchesCard: Boolean
        get() = familyName != null || givenNames != null || birthDate != null || streetAddress != null ||
            postalCode != null || locality != null || restrictedId != null
}
