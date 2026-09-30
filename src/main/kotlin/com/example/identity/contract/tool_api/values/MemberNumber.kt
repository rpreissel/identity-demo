package com.example.identity.contract.tool_api.values

/**
 * A validated Versicherungsnummer: eight digits. Only a person insured with us has one; it is then
 * also an account anchor (`AttributeType.MEMBER_NUMBER`). The one place for the format.
 */
@JvmInline
value class MemberNumber private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^\\d{8}$".toRegex()

        /** Trims, then validates [raw] - `null` if it is not eight digits. */
        fun ofOrNull(raw: String): MemberNumber? = raw.trim().let { if (PATTERN.matches(it)) MemberNumber(it) else null }

        /** Same as [ofOrNull], but throws for a malformed value. */
        fun of(raw: String): MemberNumber = requireNotNull(ofOrNull(raw)) { "Invalid member number" }
    }
}
