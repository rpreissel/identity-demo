package com.example.identity.tools.auth_kobil

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.DemoOnly
import com.example.identity.contract.tool_api.CallerKeyBinding
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.InstanceDisclosure
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import org.springframework.stereotype.Component

/** Shared by enroll-kobil/auth-kobil - the one place "kobil" is spelled out. */
internal const val KOBIL_METHOD = "kobil"

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-kobil writes and auth-kobil reads back. */
internal const val KOBIL_ENROLLMENT_TYPE = "auth_kobil.enrollment"

/**
 * The `details` key [AuthKobilDescriptor.keyBinding] reads, private to this module. It holds the
 * enrolling channel's DPoP binding key: at offer time that is all that is known about the caller.
 */
internal const val KOBIL_BINDING_KEY_REF = "kobilBindingKeyRef"

/**
 * The KOBIL device identifier in `instanceDetails`. Unlike [KOBIL_BINDING_KEY_REF] it is only known
 * after redeeming an OTP, so it cannot filter offers; a redeemed assertion is compared against it.
 */
internal const val KOBIL_DEVICE_ID = "kobilDeviceId"

/**
 * The credential lives on one phone: the KOBIL activation and the local unlock secret belong to
 * that installation. Offering it elsewhere would propose something the caller cannot complete,
 * hence the same `allowsMultipleInstances` + `keyBinding` pair as `auth_device`.
 */
private val KOBIL_KEY_BINDING = CallerKeyBinding { instanceDetails, callerBindingKeyRef ->
    instanceDetails?.get(KOBIL_BINDING_KEY_REF) == callerBindingKeyRef
}

/**
 * Shows the identifier KOBIL gave that phone, which the client cannot learn any other way. The
 * orchestrator asks this function, never the detail map ([ToolDescriptor.instanceDisclosure]).
 */
private val KOBIL_INSTANCE_DISCLOSURE = InstanceDisclosure { it?.get(KOBIL_DEVICE_ID) as? String }

/**
 * Self-description for every auth_kobil tool (docs/03-tool-architektur.md #1), one bean per toolId.
 * `factorTypes`/`maxAcr` are identical on both tools, because a method's ceiling is also resolved
 * by method name alone.
 */
@Component
object EnrollKobilDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-kobil")
    override val role = MethodRole.ENROLLMENT
    override val method = KOBIL_METHOD
    /** Not the role default `enroll`: the one thing the client does here is run the SDK's activation. */
    override val startStep = "activate"
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA2
    override val demoOnly = SIMULATED_KOBIL
    override val allowsMultipleInstances = true
    override val keyBinding = KOBIL_KEY_BINDING
    override val instanceDisclosure = KOBIL_INSTANCE_DISCLOSURE
}

/**
 * maxAcr=loa2: one run combines possession of the KOBIL-bound device with the access means
 * (pin=KNOWLEDGE, biometric=INHERENCE). Possession is fetched from KOBIL itself, not signed by the
 * client. The access means no server can see; counted under the same reservation as `auth_device`
 * (ADR-21, docs/04-orchestrierung.md #8). `startStep` is `unlock`: the PIN is released first.
 */
@Component
object AuthKobilDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-kobil")
    override val role = MethodRole.IDENTIFIED_AUTH
    override val method = KOBIL_METHOD
    override val startStep = "unlock"
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA2
    override val demoOnly = SIMULATED_KOBIL
    override val allowsMultipleInstances = true
    override val keyBinding = KOBIL_KEY_BINDING
    override val instanceDisclosure = KOBIL_INSTANCE_DISCLOSURE
}

/** ADR-36: the level rests on a simulated counterpart, not on anything this instance can check. */
private val SIMULATED_KOBIL = DemoOnly(
    "Die KOBIL-Gegenstelle ist simuliert (kobil); was ein echter KOBIL-Server zusagt, steht noch aus"
)
