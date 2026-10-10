package com.example.identity.tools.auth_device.internal.authdevice
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.credentials.requireEnrollment
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_device.DeviceModule
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_device.internal.DeviceEnrollmentRepository

import com.example.identity.tools.auth_device.internal.DEVICE_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.device.DevicePublicKey
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-device (docs/verfahren/device.md). [start]'s [enrollmentRef] is resolved by the
 * controller. No server-issued challenge: the proof's htu binds it to this single-use tool session
 * URL, and DeviceProofValidator's replay protection covers the rest, like ordinary DPoP proofs.
 */
@Component
class AuthDeviceToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: DeviceEnrollmentRepository,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId, enrollmentRef: EnrollmentRef): ToolOutcome {
        enrollmentRepository.requireEnrollment(enrollmentRef, DEVICE_ENROLLMENT_TYPE)

        sessions.save(toolSessionId, AuthDeviceToolSession(enrollmentRefId = enrollmentRef.id))
        return outcomeFor()
    }

    /** [devicePublicKey]/[userVerification] arrive already verified by DeviceProofValidator. */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, devicePublicKey: DevicePublicKey, userVerification: UserVerification): ToolOutcome {
        val data = sessions.require<AuthDeviceToolSession>(toolSessionId)
        val enrollment = enrollmentRepository.requireEnrollment(data.enrollmentRefId, toolSessionId)

        return when (val decision = AuthDeviceFlow.decide(devicePublicKey.thumbprint, enrollment.thumbprint, userVerification)) {
            AuthDeviceDecision.WrongDevice -> ToolOutcome.Failed.KnownAccountAuth(Text("Geraet nicht erkannt"))
            is AuthDeviceDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(DeviceModule.method, decision.userVerification.wireValue),
                factorTypes = factorTypesFor(decision.userVerification)
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        sessions.require<AuthDeviceToolSession>(toolSessionId)
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        return AuthDeviceState.describe().inProgress()
    }

    private fun factorTypesFor(userVerification: UserVerification): Set<FactorType> = when (userVerification) {
        UserVerification.PIN -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        UserVerification.BIOMETRIC -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
    }
}
