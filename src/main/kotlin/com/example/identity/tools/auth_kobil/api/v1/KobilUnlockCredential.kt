package com.example.identity.tools.auth_kobil.api.v1

import com.example.identity.contract.tool_api.device.UserVerification
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.Schema

/**
 * What the app presents to get the PIN released: the locally stored secret behind its biometric
 * prompt, or the account password. A sealed one-of, so "both" and "neither" cannot be constructed.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = KobilUnlockCredential.BiometricUnlock::class, name = "biometric"),
    JsonSubTypes.Type(value = KobilUnlockCredential.PasswordUnlock::class, name = "password"),
)
sealed interface KobilUnlockCredential {

    /**
     * The access means this unlock amounts to, in `auth_device`'s vocabulary. The password maps to
     * `PIN`, not to an amr entry of its own: it unlocks this credential and is not a second login.
     * A `password` amr would attach the real password enrollment and count it twice. Derived, so a
     * client never sends it.
     */
    @get:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val userVerification: UserVerification

    data class BiometricUnlock(val unlockSecret: String) : KobilUnlockCredential {
        override val userVerification get() = UserVerification.BIOMETRIC
    }

    data class PasswordUnlock(val password: String) : KobilUnlockCredential {
        override val userVerification get() = UserVerification.PIN
    }
}

/** Body of `POST .../auth-kobil/pin-releases`. */
data class KobilPinReleaseRequest(val unlock: KobilUnlockCredential)
