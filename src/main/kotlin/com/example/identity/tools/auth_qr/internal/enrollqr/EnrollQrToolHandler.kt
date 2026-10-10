package com.example.identity.tools.auth_qr.internal.enrollqr

import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.tools.auth_qr.internal.QR_OPTIN_ENROLLMENT_TYPE
import com.example.identity.tools.auth_qr.internal.QrOptIn
import com.example.identity.tools.auth_qr.internal.QrOptInRepository
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * toolId=enroll-qr: a pure opt-in without credential handshake (docs/verfahren/qr.md). The
 * PATCH call itself is the confirmation.
 */
@Component
class EnrollQrToolHandler(
    private val sessions: ToolSessionData,
    private val qrOptInRepository: QrOptInRepository,
    private val clock: Clock
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, EnrollQrToolSession())
        return outcomeFor()
    }

    @Transactional
    fun patch(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.require<EnrollQrToolSession>(toolSessionId)

        val optIn = qrOptInRepository.save(QrOptIn(clock.instant()))
        return ToolOutcome.Completed.Enrolled(
            enrollmentRef = EnrollmentRef(type = QR_OPTIN_ENROLLMENT_TYPE, id = optIn.id.toString()),
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        sessions.require<EnrollQrToolSession>(toolSessionId)
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress = ToolOutcome.InProgress(nextStep = ToolRole.ENROLLMENT.defaultStartStep)
}
