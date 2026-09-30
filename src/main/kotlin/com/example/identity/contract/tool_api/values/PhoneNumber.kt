package com.example.identity.contract.tool_api.values

/**
 * A normalized mobile number we may send an SMS to: the one place for normalization and the
 * allowed numbers. The send budget, `enroll-sms` and the stored enrollment must agree on it,
 * or one number could count as three for the rate limit.
 */
@JvmInline
value class PhoneNumber private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val SEPARATORS = "[\\s\\-/().]".toRegex()
        private val E164 = "^\\+[1-9][0-9]{7,14}$".toRegex()

        /**
         * EU member states plus the EEA (Iceland, Liechtenstein, Norway): SMS to premium destinations
         * is the classic abuse of a public "send me a code" endpoint ("SMS pumping"), and nobody
         * registers here with a number from elsewhere.
         */
        private val ALLOWED_COUNTRY_CODES = setOf(
            "43", "32", "359", "385", "357", "420", "45", "372", "358", "33", "49", "30", "36", "353", "39",
            "371", "370", "352", "356", "31", "48", "351", "40", "421", "386", "34", "46",
            "354", "423", "47",
        )

        /** Separators out, a leading `00` becomes `+` - "+49 170 123 45-67" and "0049 (170) 1234567" are the same number. */
        fun normalize(raw: String): String =
            raw.replace(SEPARATORS, "").let { if (it.startsWith("00")) "+" + it.removePrefix("00") else it }

        /** Normalizes, then checks E.164 and the EU/EEA country code - `null` if it is no number we send to. */
        fun ofOrNull(raw: String): PhoneNumber? {
            val number = normalize(raw)
            if (!E164.matches(number)) return null
            val digits = number.removePrefix("+")
            return if (ALLOWED_COUNTRY_CODES.any { digits.startsWith(it) && digits.length > it.length + 5 }) PhoneNumber(number) else null
        }

        /** Same as [ofOrNull], but throws for a number we do not send to. */
        fun of(raw: String): PhoneNumber = requireNotNull(ofOrNull(raw)) { "Not a mobile number we send to" }
    }
}
