package com.example.identity.contract.tool_api.values

/**
 * A validated, normalized email address: the one place for format and normalization (trim,
 * lowercase). Backs [normalizeAnchorValue], so anchor writes and lookups agree.
 */
@JvmInline
value class Email private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".toRegex()

        /**
         * The longest address SMTP can deliver to (RFC 5321). Longer would also overflow the
         * 255-character columns as an internal error.
         */
        private const val MAX_LENGTH = 254

        /** Normalizes (trim + lowercase) then validates [raw] - `null` if it is not well-formed. */
        fun ofOrNull(raw: String): Email? {
            val normalized = raw.trim().lowercase()
            return if (normalized.length <= MAX_LENGTH && PATTERN.matches(normalized)) Email(normalized) else null
        }

        /** Same as [ofOrNull], but throws for a malformed address. */
        fun of(raw: String): Email = requireNotNull(ofOrNull(raw)) { "Invalid email address" }
    }
}
