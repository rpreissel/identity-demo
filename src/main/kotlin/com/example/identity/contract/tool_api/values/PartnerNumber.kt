package com.example.identity.contract.tool_api.values

/**
 * A validated Partnernummer: `P` and nine digits, uppercase. The Personenverzeichnis hands it out
 * for every person it knows (ADR-34), and it is the person id everywhere else: the `PERSON_ID`
 * anchor, the ID claims, Keycloak. The one place for the format.
 */
@JvmInline
value class PartnerNumber(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid partner number" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = "^P\\d{9}$".toRegex()

        /** Input from outside: normalizes (trim + uppercase), `null` if it is not well-formed. */
        fun parse(raw: String): PartnerNumber? = raw.trim().uppercase().takeIf(PATTERN::matches)?.let(::PartnerNumber)
    }
}
