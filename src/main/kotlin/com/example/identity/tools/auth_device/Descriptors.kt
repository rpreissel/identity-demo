package com.example.identity.tools.auth_device

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.CallerKeyBinding
import com.example.identity.contract.tool_api.DemoOnly
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.InstanceDisclosure
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import org.springframework.stereotype.Component

/** Shared by enroll-device/auth-device - the one place "device" is spelled out. */
internal const val DEVICE_METHOD = "device"

/** The `details` key [AuthDeviceDescriptor.keyBinding] reads and enroll-device writes; module-private. */
internal const val DEVICE_BINDING_KEY_REF = "deviceBindingKeyRef"

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-device writes and auth-device reads back. */
internal const val DEVICE_ENROLLMENT_TYPE = "auth_device.enrollment"

/** Self-description for every auth_device tool (docs/03-tool-architektur.md #1), one bean per toolId. */
@Component
object AuthDeviceDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-device")
    override val role = MethodRole.IDENTIFIED_AUTH
    override val method = DEVICE_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA2
    // Declared per tool variant, like maxAcr/factorTypes; callers resolve the descriptor by
    // (method, role), never by method name alone.
    override val allowsMultipleInstances = true

    /**
     * The credential is a non-extractable key on one device: it only works there and is revoked
     * once the key is rebound to another account ([ToolDescriptor.keyBinding]). Reads
     * [DEVICE_BINDING_KEY_REF], so generic resolution never needs the key name. A null caller
     * (WEB) never matches a device-enrolled instance.
     */
    override val keyBinding = CallerKeyBinding { instanceDetails, callerBindingKeyRef ->
        instanceDetails?.get(DEVICE_BINDING_KEY_REF) == callerBindingKeyRef
    }

    /**
     * The credential key, the second key this device carries next to its DPoP channel key
     * (docs/09-dpop.md). The orchestrator asks this function, never the map.
     */
    override val instanceDisclosure = InstanceDisclosure { it?.get(DEVICE_BINDING_KEY_REF) as? String }

    override val demoOnly = UNATTESTED_USER_VERIFICATION
}

/**
 * Both device tools count the PIN or biometric check as a second factor (loa2), but the server only
 * sees a `userVerification` claim the app signs itself. Without platform attestation that is a
 * demonstration, not a proof (ADR-36).
 */
private val UNATTESTED_USER_VERIFICATION = DemoOnly(
    "Die Nutzerverifikation (PIN/Biometrie) ist nur vom Client behauptet, ohne Plattform-Attestation"
)

/**
 * maxAcr=loa2 and factorTypes cover both access-means outcomes (pin=KNOWLEDGE, biometric=INHERENCE,
 * plus POSSESSION of the key), because one successful run already combines two factor types.
 */
@Component
object EnrollDeviceDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-device")
    override val role = MethodRole.ENROLLMENT
    override val method = DEVICE_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA2
    override val allowsMultipleInstances = true
    override val demoOnly = UNATTESTED_USER_VERIFICATION
}
