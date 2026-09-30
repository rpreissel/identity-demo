package com.example.identity.contract.tool_api.values

/**
 * A validated, normalized Krankenversichertennummer: one letter and nine digits, uppercase
 * (e.g. `A123456789`). Backs [normalizeAnchorValue]'s `KVNR` case.
 */
@JvmInline
value class Kvnr(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid Kvnr" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = "^[A-Z]\\d{9}$".toRegex()

        /** Input from outside: normalizes (trim + uppercase), `null` if it is not well-formed. */
        fun parse(raw: String): Kvnr? = raw.trim().uppercase().takeIf(PATTERN::matches)?.let(::Kvnr)
    }
}
