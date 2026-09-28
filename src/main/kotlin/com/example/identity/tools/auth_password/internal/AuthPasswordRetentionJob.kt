package com.example.identity.tools.auth_password.internal
import com.example.identity.tools.auth_password.internal.authpasswordlookup.AuthPasswordLookupToolSessionRepository
import com.example.identity.tools.auth_password.internal.authpassword.AuthPasswordToolSessionRepository
import com.example.identity.tools.auth_password.internal.enrollpassword.EnrollPasswordToolSessionRepository

import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3). AuthPasswordEnrollment is out of scope: it belongs
 * to the account, not the session.
 */
@Component
class AuthPasswordRetentionJob(
    private val enrollToolSessionRepository: EnrollPasswordToolSessionRepository,
    private val authUseToolSessionRepository: AuthPasswordToolSessionRepository,
    private val authLookupToolSessionRepository: AuthPasswordLookupToolSessionRepository
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authUseToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

}
