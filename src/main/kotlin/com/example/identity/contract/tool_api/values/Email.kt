package com.example.identity.contract.tool_api.values

/**
 * A validated, normalized email address: the one place for format and normalization (trim,
 * lowercase). Backs [normalizeAnchorValue], so anchor writes and lookups agree.
 */
@JvmInline
value class Email(val value: String) {
    init {
        require(value.length <= MAX_LENGTH && value == value.trim().lowercase() && PATTERN.matches(value)) { "Invalid email address" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".toRegex()

        /**
         * The longest address SMTP can deliver to (RFC 5321). Longer would also overflow the
         * 255-character columns as an internal error.
         */
        private const val MAX_LENGTH = 254

        /** Input from outside: normalizes (trim + lowercase), `null` if it is not well-formed. */
        fun parse(raw: String): Email? =
            raw.trim().lowercase().takeIf { it.length <= MAX_LENGTH && PATTERN.matches(it) }?.let(::Email)
    }
}
