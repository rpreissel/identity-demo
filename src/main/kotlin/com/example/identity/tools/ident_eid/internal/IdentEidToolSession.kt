package com.example.identity.tools.ident_eid.internal

import java.time.LocalDate

/** The working data of one ident-eid run, kept through `ToolSessionData`. */
internal data class IdentEidToolSession(
    /** The simulated eID card's Ausweisdaten, the first stage of the "input" step. */
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    val streetAddress: String? = null,
    val postalCode: String? = null,
    val locality: String? = null,

    /** The card's restricted identifier - person-unique pseudonym, ADR-19's recognition anchor. */
    val restrictedId: String? = null,

    /** SHA-256 of the submitted PIN - the PIN itself is never persisted. */
    val pinHash: String? = null,
)
