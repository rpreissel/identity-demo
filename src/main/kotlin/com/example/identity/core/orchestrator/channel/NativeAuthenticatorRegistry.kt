package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.FactorType
import org.springframework.stereotype.Component

/**
 * Fixed metadata per native Keycloak authenticator type (docs/05-api.md Abschnitt 3), the kc
 * facade's mirror of `Tool`. Looked up by [nativeToolId], the stable config id, not the
 * per-proof `amrSourceId`. So an `AmrEntry` sends only the two ids; method, ceiling and factor
 * types come from here.
 */
data class NativeAuthenticatorDescriptor(
    val nativeToolId: String,
    val method: String,
    val maxAcr: String,
    val factorTypes: Set<FactorType>,
)

/**
 * Demo registry: a fixed set standing in for what a real deployment configures per authenticator
 * execution. Separate from `ToolHandlerRegistry`, because native authenticators are no orchestrator
 * tools and the tool catalog must not know them.
 */
@Component
class NativeAuthenticatorRegistry {
    // The same loa1 ceiling as the orchestrator counterparts (AuthPasswordDescriptor,
    // AuthSmsDescriptor). Two distinct native methods still combine to loa2 via the MFA bump
    // (see the uncapped enrolledUnderAcr in KeycloakChannelService).
    private val byId: Map<String, NativeAuthenticatorDescriptor> = listOf(
        NativeAuthenticatorDescriptor("kc-password-form", "password", "loa1", setOf(FactorType.KNOWLEDGE)),
        NativeAuthenticatorDescriptor("kc-otp-form", "otp", "loa1", setOf(FactorType.POSSESSION)),
        NativeAuthenticatorDescriptor("kc-sms-form", "sms", "loa1", setOf(FactorType.POSSESSION)),
    ).associateBy { it.nativeToolId }

    fun descriptorFor(nativeToolId: String): NativeAuthenticatorDescriptor? = byId[nativeToolId]
}
