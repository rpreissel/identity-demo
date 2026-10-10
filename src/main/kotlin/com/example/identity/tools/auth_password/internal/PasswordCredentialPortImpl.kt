package com.example.identity.tools.auth_password.internal

import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.EnrollmentRef
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Implements [PasswordCredentialPort] for callers outside a ToolSession (KOBIL's unlock by password),
 * with the same hashing and storage as the tools.
 */
@Component
internal class PasswordCredentialPortImpl(
    private val enrollmentRepository: AuthPasswordEnrollmentRepository,
) : PasswordCredentialPort {

    @Transactional
    override fun verify(enrollmentRef: EnrollmentRef?, candidate: String): Boolean {
        val enrollment = enrollmentRef?.id?.toLongOrNull()?.let { enrollmentRepository.findByIdOrNull(it) }
        // Unconditional, constant-cost check regardless of whether an enrollment was found - see
        // PasswordHasher.matches's KDoc on why a null-guarded short-circuit would reopen a
        // timing-based account-enumeration oracle.
        val matches = PasswordHasher.matches(candidate, enrollment?.passwordHash)
        if (matches && enrollment != null) PasswordHasher.upgrade(enrollment, candidate)
        return matches
    }
}
