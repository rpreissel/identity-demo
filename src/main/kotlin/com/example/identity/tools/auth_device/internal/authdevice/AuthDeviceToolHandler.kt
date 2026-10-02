package com.example.identity.tools.auth_device.internal.authdevice
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
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * toolId=auth-device (docs/03-tool-architektur.md). [start]'s [enrollmentRef] is resolved by the
 * controller. No server-issued challenge: the proof's htu binds it to this single-use tool session
 * URL, and DeviceProofValidator's replay protection covers the rest, like ordinary DPoP proofs.
 */
@Component
class AuthDeviceToolHandler(
    private val toolDataRepository: AuthDeviceToolSessionRepository,
    private val enrollmentRepository: DeviceEnrollmentRepository,
    private val clock: Clock
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId, enrollmentRef: EnrollmentRef): ToolOutcome {
        if (enrollmentRef.type != DEVICE_ENROLLMENT_TYPE) {
            throw UnresolvableReferenceException(Text("Unerwarteter Enrollment-Typ"), "type=${enrollmentRef.type}")
        }
        val enrollmentId = enrollmentRef.id.toLongOrNull()
            ?: throw UnresolvableReferenceException(Text("Ungueltige Enrollment-Referenz"), "id=${enrollmentRef.id}")
        enrollmentRepository.findByIdOrNull(enrollmentId)
            ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "id=${enrollmentRef.id}")

        toolDataRepository.save(
            AuthDeviceToolSession(
                toolSessionId = toolSessionId,
                enrollmentRefId = enrollmentRef.id,
                createdAt = clock.instant()
            )
        )
        return outcomeFor()
    }

    /** [devicePublicKey]/[userVerification] arrive already verified by DeviceProofValidator. */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, devicePublicKey: DevicePublicKey, userVerification: UserVerification): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByToolSessionId(toolSessionId)) { "Unknown auth-device tool session: $toolSessionId" }
        // Gone during the tool session (removed on another channel): no wrong guess, nothing to count.
        val enrollment = data.enrollmentRefId?.toLongOrNull()?.let { enrollmentRepository.findByIdOrNull(it) }
            ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "toolSession=$toolSessionId")

        return when (val decision = AuthDeviceFlow.decide(devicePublicKey.thumbprint, enrollment.thumbprint, userVerification)) {
            AuthDeviceDecision.WrongDevice -> ToolOutcome.Failed.KnownAccountAuth(Text("Geraet nicht erkannt"))
            is AuthDeviceDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(DeviceModule.method, decision.userVerification.wireValue),
                factorTypes = factorTypesFor(decision.userVerification)
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        checkNotNull(toolDataRepository.findByToolSessionId(toolSessionId)) { "Unknown auth-device tool session: $toolSessionId" }
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        val (step, fields) = AuthDeviceState.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun factorTypesFor(userVerification: UserVerification): Set<FactorType> = when (userVerification) {
        UserVerification.PIN -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        UserVerification.BIOMETRIC -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
    }
}
