package com.example.identity.contract.tool_api

/**
 * Marks a controller parameter that receives the proven caller ([CallerProof]). It is validated
 * before the method runs; a controller never reads the DPoP header or the peer-auth assertion itself.
 *
 * The parameter's type says which proofs the endpoint accepts: [CallerProof] for both channels,
 * [CallerProof.DeviceKey] for endpoints of the App channel alone, since only an App channel carries a
 * key (docs/invarianten.md I-8).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class BindingKey
