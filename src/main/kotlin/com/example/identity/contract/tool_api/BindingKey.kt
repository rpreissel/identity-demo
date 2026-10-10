package com.example.identity.contract.tool_api

/**
 * Marks a controller parameter that receives the caller's resolved DPoP binding key
 * (`bindingKeyRef: String`). It is validated before the method runs; a tool controller never
 * reads the DPoP header itself.
 *
 * [dpopOnly]: only a DPoP proof is accepted, never Keycloak's peer-auth assertion, for endpoints of
 * the App channel alone; only an App channel carries a key (docs/invarianten.md I-8).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class BindingKey(val dpopOnly: Boolean = false)
