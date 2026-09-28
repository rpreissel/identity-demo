package com.example.identity.tools.auth_device.internal.enrolldevice
import com.example.identity.tools.auth_device.internal.DeviceEnrollmentRepository
import com.example.identity.tools.auth_device.internal.DeviceEnrollment

import com.example.identity.tools.auth_device.DEVICE_BINDING_KEY_REF
import com.example.identity.tools.auth_device.DEVICE_ENROLLMENT_TYPE
import com.example.identity.tools.auth_device.EnrollDeviceDescriptor
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.device.DevicePublicKey
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-device (docs/03-tool-architektur.md): registers a device-bound key pair as a new
 * credential. The input decision lives in [EnrollDeviceFlow].
 */
@Component
class EnrollDeviceToolHandler(
    private val descriptor: EnrollDeviceDescriptor,
    private val toolDataRepository: EnrollDeviceToolSessionRepository,
    private val enrollmentRepository: DeviceEnrollmentRepository
) {

    /** Called directly by EnrollDeviceToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollDeviceToolSession(toolSessionId = toolSessionId))
        return outcomeFor()
    }

    /**
     * [devicePublicKey]/[userVerification] arrive verified by DeviceProofValidator. [deviceBindingKeyRef]
     * is the enrolling channel's DPoP fingerprint, so later logins offer this credential only on
     * the device that holds it.
     */
    @Transactional
    fun patch(toolSessionId: UUID, devicePublicKey: DevicePublicKey, userVerification: UserVerification, deviceBindingKeyRef: String, label: String?): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-device tool session: $toolSessionId" }

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
                    thumbprint = decision.devicePublicKey.thumbprint
                )
            )

        return ToolOutcome.Completed.Enrolled(
            enrollmentRef = EnrollmentRef(type = DEVICE_ENROLLMENT_TYPE, id = enrollment.id.toString()),
            amr = listOf(descriptor.method, decision.userVerification.wireValue),
            achievedAcr = descriptor.maxAcr,
            factorTypes = factorTypesFor(decision.userVerification),
            instanceDetails = mapOf(DEVICE_BINDING_KEY_REF to decision.deviceBindingKeyRef),
            label = decision.label
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-device tool session: $toolSessionId" }
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
