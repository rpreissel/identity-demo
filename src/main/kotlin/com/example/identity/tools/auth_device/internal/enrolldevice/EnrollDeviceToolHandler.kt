package com.example.identity.tools.auth_device.internal.enrolldevice
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_device.DeviceModule
import com.example.identity.tools.auth_device.internal.DeviceEnrollmentRepository
import com.example.identity.tools.auth_device.internal.DeviceEnrollment

import com.example.identity.tools.auth_device.internal.DEVICE_ENROLLMENT_TYPE
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.device.DevicePublicKey
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * toolId=enroll-device (docs/03-tool-architektur.md): registers a device-bound key pair as a new
 * credential. The input decision lives in [EnrollDeviceFlow].
 */
@Component
class EnrollDeviceToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: DeviceEnrollmentRepository,
    private val clock: Clock
) {

    /** Called directly by EnrollDeviceToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, EnrollDeviceToolSession())
        return outcomeFor()
    }

    /**
     * [devicePublicKey]/[userVerification] arrive verified by DeviceProofValidator. [deviceBindingKeyRef]
     * is the enrolling channel's DPoP fingerprint, so later logins offer this credential only on
     * the device that holds it.
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, devicePublicKey: DevicePublicKey, userVerification: UserVerification, deviceBindingKeyRef: String, label: String?): ToolOutcome {
        sessions.require<EnrollDeviceToolSession>(toolSessionId)

        val decision = when (val decided = EnrollDeviceFlow.decide(EnrollDeviceInput(devicePublicKey, userVerification, deviceBindingKeyRef, label))) {
            EnrollDeviceDecision.SameKeyAsChannel ->
                return ToolOutcome.Failed.NothingGuessed(Text("Der Geräteschlüssel muss ein anderer sein als der Schlüssel dieser Sitzung"))
            is EnrollDeviceDecision.Enroll -> decided
        }

        // The client keeps its device key, so re-enrolling on the same device resubmits the same
        // thumbprint. Reuse that row; a second insert would violate the unique thumbprint.
        val enrollment = enrollmentRepository.findByThumbprint(decision.devicePublicKey.thumbprint)
            ?: enrollmentRepository.save(
                DeviceEnrollment(
                    kty = decision.devicePublicKey.kty,
                    crv = decision.devicePublicKey.crv,
                    x = decision.devicePublicKey.x,
                    y = decision.devicePublicKey.y,
                    thumbprint = decision.devicePublicKey.thumbprint,
                    createdAt = clock.instant()
                )
            )

        return ToolOutcome.Completed.Enrolled(
            enrollmentRef = EnrollmentRef(type = DEVICE_ENROLLMENT_TYPE, id = enrollment.id.toString()),
            amr = listOf(DeviceModule.method, decision.userVerification.wireValue),
            factorTypes = factorTypesFor(decision.userVerification),
            // The channel's DPoP key this credential lives on, shown as its reference on that device.
            boundKeyRef = decision.deviceBindingKeyRef,
            reference = decision.deviceBindingKeyRef,
            label = decision.label
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.require<EnrollDeviceToolSession>(toolSessionId)
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        val (step, fields) = EnrollDeviceState.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun factorTypesFor(userVerification: UserVerification): Set<FactorType> = when (userVerification) {
        UserVerification.PIN -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        UserVerification.BIOMETRIC -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
    }
}
