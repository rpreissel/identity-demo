package com.example.identity.tools.ident_fsc.internal

import com.example.identity.contract.tool_api.values.PartnerNumber
import java.time.LocalDate

/** The working data of one ident-fsc run (docs/06-ablaeufe.md #1), kept through `ToolSessionData`. */
internal data class IdentFscToolSession(
    val kvnr: String? = null,
    val partnerNumber: String? = null,
    val personId: PartnerNumber? = null,
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    /** SHA-256 of the submitted code - the code itself is never persisted. */
    val fscHash: String? = null,
)
