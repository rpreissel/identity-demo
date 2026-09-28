package com.example.identity.tools.auth_password.internal

import com.example.identity.tools.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.EnrollmentRef
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Implements [PasswordCredentialPort] for callers outside a ToolSession (Keycloak's native password
 * credential), with the same hashing and storage as the tools.
 */
@Component
internal class PasswordCredentialPortImpl(
    private val enrollmentRepository: AuthPasswordEnrollmentRepository,
    private val clock: Clock
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

    @Transactional
    override fun setNew(password: String): EnrollmentRef {
        PasswordPolicy.check(password)?.let(PasswordPolicy::reject)
        val enrollment = enrollmentRepository.save(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(password), createdAt = clock.instant()))
        return EnrollmentRef(type = PASSWORD_ENROLLMENT_TYPE, id = enrollment.id.toString())
    }
}
