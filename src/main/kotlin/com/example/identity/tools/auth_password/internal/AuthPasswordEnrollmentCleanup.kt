package com.example.identity.tools.auth_password.internal

import com.example.identity.contract.tool_api.credentials.EnrollmentCleanup
import com.example.identity.contract.tool_api.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class AuthPasswordEnrollmentCleanup(
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) : EnrollmentCleanup {
    override val enrollmentType = PASSWORD_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        enrollmentRepository.deleteById(enrollmentRef.id.toLong())
    }
}
