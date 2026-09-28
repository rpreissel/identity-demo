package com.example.identity.contract.tool_api.device

import jakarta.servlet.http.HttpServletRequest

/**
 * The public half of a device-binding key pair, already verified; modules never parse a raw JWK
 * or proof JWT themselves. The fields are the JWK fields of an EC public key.
 *
 * @property thumbprint the JWK SHA-256 thumbprint (RFC 7638), used to look up and compare device
 * enrollments.
 */
data class DevicePublicKey(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String,
    val thumbprint: String
)

/**
 * How the user unlocked a device key to produce a proof. The app attests this itself; the server
 * cannot verify it (ADR-36).
 *
 * @property wireValue the value of the proof JWT's `userVerification` claim.
 */
enum class UserVerification(val wireValue: String) {
    /** A PIN or passphrase - counts as [com.example.identity.contract.tool_api.FactorType.KNOWLEDGE]. */
    PIN("pin"),
    /** A biometric unlock - counts as [com.example.identity.contract.tool_api.FactorType.INHERENCE]. */
    BIOMETRIC("biometric");

    companion object {
        /**
         * Reverse of [wireValue]. `null` for an absent or unknown claim; the caller then treats
         * the proof as possession only.
         */
        fun fromWireValue(value: String?): UserVerification? = entries.find { it.wireValue == value }
    }
}

/**
 * The outcome of a successfully validated device-binding proof.
 *
 * @property publicKey the device key the proof was signed with.
 * @property userVerification how the user unlocked the device key for this proof.
 */
data class VerifiedDeviceProof(val publicKey: DevicePublicKey, val userVerification: UserVerification)

/** Validates device-binding proofs sent by an app client for device-bound tools. */
interface DeviceProofs {
    /**
     * Validates a device-binding proof JWT (`typ="device-proof+jwt"`) against [request]: its
     * method against `htm`, the URL the client called against `htu`.
     *
     * @throws RuntimeException if [deviceProof] is missing, malformed, expired, already used, or
     * does not match the request; mapped to `401`.
     */
    fun validate(deviceProof: String?, request: HttpServletRequest): VerifiedDeviceProof
}
