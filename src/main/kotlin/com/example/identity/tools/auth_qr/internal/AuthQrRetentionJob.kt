package com.example.identity.tools.auth_qr.internal

import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3) of short-lived module data: the QR login requests past
 * their own expiry. The tools' working data lives with orchestrator.tool_session and ends with it;
 * QrOptIn (the long-lived opt-in) belongs to the account and is out of scope.
 */
@Component
class AuthQrRetentionJob(
    private val loginRequestRepository: QrLoginRequestRepository
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        loginRequestRepository.deleteByExpiresAtBefore(cutoff)
    }

}
