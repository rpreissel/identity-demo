package com.example.identity.tools.auth_device

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.FactorType.INHERENCE
import com.example.identity.contract.tool_api.FactorType.KNOWLEDGE
import com.example.identity.contract.tool_api.FactorType.POSSESSION
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

internal const val ENROLL_DEVICE_TOOL_ID = "enroll-device"
internal const val AUTH_DEVICE_TOOL_ID = "auth-device"

/**
 * The device procedure: `enroll-device`, `auth-device` (docs/03-tool-architektur.md #1). The
 * credential is a non-extractable key on one device: it only works there and is revoked once the
 * key is rebound to another account (docs/09-dpop.md). One run combines possession of the key with
 * the access means (pin = KNOWLEDGE, biometric = INHERENCE), hence loa2. The server only sees a
 * `userVerification` claim the app signs itself; without platform attestation that is a
 * demonstration, not a proof (ADR-36).
 */
internal val DeviceModule = toolModule(
    method = "device",
    name = Text("Gerät"),
    proves = factors(POSSESSION, KNOWLEDGE, INHERENCE, upTo = AcrLevel.LOA2),
    demoOnly = "Die Nutzerverifikation (PIN/Biometrie) ist nur vom Client behauptet, ohne Plattform-Attestation",
    onePerDevice = true,
)

internal val EnrollDevice = DeviceModule.enroll(
    ENROLL_DEVICE_TOOL_ID,
    versions = setOf(1),
    hint = Text("Geräteeigener Schlüssel + PIN/Biometrie"),
)
internal val AuthDevice = DeviceModule.login(
    AUTH_DEVICE_TOOL_ID,
    versions = setOf(1),
    hint = Text("Geräteeigener Schlüssel + PIN/Biometrie"),
)

/**
 * A method module talks to the orchestrator through tool_api only, never to account or another
 * method module (docs/03-tool-architektur.md #2). Its controllers (`auth_device.api.v1`) reach the
 * orchestrator through `tool_api` alone (docs/04-orchestrierung.md #5).
 */
@ApplicationModule(id = "auth_device", allowedDependencies = ["tool_api", "texts"])
@Configuration
internal class DeviceToolModule {
    @Bean
    fun deviceModule() = DeviceModule
}
