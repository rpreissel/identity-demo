package com.example.identity.tools.auth_password.internal

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.InvalidInputException
/**
 * What a new password must satisfy, for the enroll-password tool and the change through Keycloak.
 * [MAX_LENGTH] fits any passphrase but keeps anyone from hashing megabytes. [COMMON] lists the
 * passwords every guessing attack tries first, compared case-insensitively.
 */
internal object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    enum class Rejection { TOO_SHORT, TOO_LONG, TOO_COMMON }

    fun check(password: String): Rejection? = when {
        password.length < MIN_LENGTH -> Rejection.TOO_SHORT
        password.length > MAX_LENGTH -> Rejection.TOO_LONG
        password.lowercase() in COMMON -> Rejection.TOO_COMMON
        else -> null
    }

    /** What the user is told - the same wording wherever a password is set. */
    fun message(rejection: Rejection): Text = when (rejection) {
        Rejection.TOO_SHORT -> Text("Das Passwort ist zu kurz (mindestens {min} Zeichen).", "min" to MIN_LENGTH)
        Rejection.TOO_LONG -> Text("Das Passwort ist zu lang (höchstens {max} Zeichen).", "max" to MAX_LENGTH)
        Rejection.TOO_COMMON -> Text("Dieses Passwort ist zu verbreitet. Bitte wählen Sie ein anderes.")
    }

    /** The rejection as the 400 the caller gets. */
    fun reject(rejection: Rejection): Nothing = throw InvalidInputException(message(rejection))

    private val COMMON: Set<String> = PasswordPolicy::class.java.getResourceAsStream("/auth_password/common-passwords.txt")
        .let { checkNotNull(it) { "common-passwords.txt missing" } }
        .bufferedReader().useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet() }
}
