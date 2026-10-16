package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.ids.ChannelSessionId

/**
 * Who is calling, as proven by the request (docs/09-dpop.md #3, docs/05-api.md Abschnitt 3b). Filled
 * by the orchestrator for a [BindingKey] parameter or a tool context, never taken from the URL.
 */
sealed interface CallerProof {
    /** An App call, signed with this device's key (DPoP); [thumbprint] is that key's fingerprint. */
    data class DeviceKey(val thumbprint: String) : CallerProof

    /** A Web call: Keycloak's signed peer-auth assertion for the flow run of [channelSessionId]. */
    data class KeycloakFlow(val channelSessionId: ChannelSessionId) : CallerProof

    /** The device key of an App call. Only a tool that runs on the App alone asks for it. */
    fun requireDeviceKey(): DeviceKey = this as? DeviceKey ?: error("Only an App call carries a device key")
}
