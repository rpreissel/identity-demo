package com.example.identity.tools.auth_qr.internal.enrollqr

import com.example.identity.tools.auth_qr.EnrollQrDescriptor
import com.example.identity.tools.auth_qr.QR_OPTIN_ENROLLMENT_TYPE
import com.example.identity.tools.auth_qr.internal.QrOptIn
import com.example.identity.tools.auth_qr.internal.QrOptInRepository
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * toolId=enroll-qr: a pure opt-in without credential handshake (docs/03-tool-architektur.md). The
 * PATCH call itself is the confirmation.
 */
@Component
class EnrollQrToolHandler(
    private val descriptor: EnrollQrDescriptor,
    private val toolDataRepository: EnrollQrToolSessionRepository,
    private val qrOptInRepository: QrOptInRepository,
    private val clock: Clock
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollQrToolSession(toolSessionId = toolSessionId, createdAt = clock.instant()))
        return outcomeFor()
    }

    @Transactional
    fun patch(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-qr tool session: $toolSessionId" }

        val optIn = qrOptInRepository.save(QrOptIn(clock.instant()))
        return ToolOutcome.Completed.Enrolled(
            enrollmentRef = EnrollmentRef(type = QR_OPTIN_ENROLLMENT_TYPE, id = optIn.id.toString()),
            amr = emptyList(),
            achievedAcr = descriptor.maxAcr,
            factorTypes = descriptor.factorTypes
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-qr tool session: $toolSessionId" }
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress = ToolOutcome.InProgress(nextStep = descriptor.startStep)
}
