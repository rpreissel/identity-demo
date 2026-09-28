package com.example.identity.contract.tool_api.values

/**
 * A validated Versicherungsnummer: eight digits. Only a person insured with us has one; it is then
 * also an account anchor (`AttributeType.INSURANCE_NUMBER`). The one place for the format.
 */
@JvmInline
value class InsuranceNumber private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^\\d{8}$".toRegex()

        /** Trims, then validates [raw] - `null` if it is not eight digits. */
        fun ofOrNull(raw: String): InsuranceNumber? = raw.trim().let { if (PATTERN.matches(it)) InsuranceNumber(it) else null }

        /** Same as [ofOrNull], but throws for a malformed value. */
        fun of(raw: String): InsuranceNumber = requireNotNull(ofOrNull(raw)) { "Invalid insurance number" }
    }
}
