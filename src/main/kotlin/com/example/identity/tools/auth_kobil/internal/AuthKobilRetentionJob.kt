package com.example.identity.tools.auth_kobil.internal

import com.example.identity.tools.auth_kobil.internal.authkobil.AuthKobilToolSessionRepository
import com.example.identity.tools.auth_kobil.internal.enrollkobil.EnrollKobilToolSessionRepository
import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3). The working data holds a PIN and an unlock secret in
 * the clear during setup, so the deadline matters more than usual. [KobilEnrollment] belongs to the
 * account and is out of scope.
 */
@Component
class AuthKobilRetentionJob(
    private val enrollToolSessionRepository: EnrollKobilToolSessionRepository,
    private val authToolSessionRepository: AuthKobilToolSessionRepository,
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

}
