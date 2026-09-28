package com.example.identity.contract.tool_api.values

/**
 * A validated, normalized Krankenversichertennummer: one letter and nine digits, uppercase
 * (e.g. `A123456789`). Backs [normalizeAnchorValue]'s `KVNR` case.
 */
@JvmInline
value class Kvnr private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^[A-Z]\\d{9}$".toRegex()

        /** Normalizes (trim + uppercase) then validates [raw] - `null` if it is not well-formed. */
        fun ofOrNull(raw: String): Kvnr? {
            val normalized = raw.trim().uppercase()
            return if (PATTERN.matches(normalized)) Kvnr(normalized) else null
        }

        /** Same as [ofOrNull], but throws for a malformed value. */
        fun of(raw: String): Kvnr = requireNotNull(ofOrNull(raw)) { "Invalid Kvnr" }
    }
}
