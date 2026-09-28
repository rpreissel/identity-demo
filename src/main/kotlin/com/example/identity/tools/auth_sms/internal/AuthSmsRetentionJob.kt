package com.example.identity.tools.auth_sms.internal
import com.example.identity.tools.auth_sms.internal.authsmslookup.AuthSmsLookupToolSessionRepository
import com.example.identity.tools.auth_sms.internal.authsms.AuthSmsToolSessionRepository
import com.example.identity.tools.auth_sms.internal.enrollsms.EnrollSmsToolSessionRepository

import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3) of TAN hashes and unconfirmed numbers.
 * AuthSmsEnrollment is out of scope: it belongs to the account, not the session.
 */
@Component
class AuthSmsRetentionJob(
    private val enrollToolSessionRepository: EnrollSmsToolSessionRepository,
    private val authUseToolSessionRepository: AuthSmsToolSessionRepository,
    private val authLookupToolSessionRepository: AuthSmsLookupToolSessionRepository
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authUseToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

}
