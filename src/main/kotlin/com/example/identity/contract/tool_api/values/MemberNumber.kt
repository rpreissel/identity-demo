package com.example.identity.contract.tool_api.values

/**
 * A validated Versicherungsnummer: eight digits. Only a person insured with us has one; it is then
 * also an account anchor (`AttributeType.MEMBER_NUMBER`). The one place for the format.
 */
@JvmInline
value class MemberNumber(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid member number" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = "^\\d{8}$".toRegex()

        /** Input from outside: trims, `null` if it is not eight digits. */
        fun parse(raw: String): MemberNumber? = raw.trim().takeIf(PATTERN::matches)?.let(::MemberNumber)
    }
}
