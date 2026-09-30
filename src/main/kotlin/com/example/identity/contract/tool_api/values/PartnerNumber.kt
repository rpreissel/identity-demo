package com.example.identity.contract.tool_api.values

/**
 * A validated Partnernummer: `P` and nine digits, uppercase. The Personenverzeichnis hands it out
 * for every person it knows (ADR-34), and it is the person id everywhere else: the `PERSON_ID`
 * anchor, the ID claims, Keycloak. The one place for the format.
 */
@JvmInline
value class PartnerNumber private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^P\\d{9}$".toRegex()

        /** Normalizes (trim + uppercase) then validates [raw] - `null` if it is not well-formed. */
        fun ofOrNull(raw: String): PartnerNumber? = raw.trim().uppercase().let { if (PATTERN.matches(it)) PartnerNumber(it) else null }

        /** Same as [ofOrNull], but throws for a malformed value. */
        fun of(raw: String): PartnerNumber = requireNotNull(ofOrNull(raw)) { "Invalid partner number" }

        /** The Partnernummer with these nine digits - how the Personenverzeichnis mints a new one. */
        fun ofDigits(digits: Int): PartnerNumber = of("P%09d".format(digits))
    }
}
