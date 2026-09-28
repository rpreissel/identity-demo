package com.example.identity.tools.auth_sms.internal

import com.example.identity.tools.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.credentials.EnrollmentCleanup
import com.example.identity.contract.tool_api.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class AuthSmsEnrollmentCleanup(
    private val enrollmentRepository: AuthSmsEnrollmentRepository
) : EnrollmentCleanup {
    override val enrollmentType = SMS_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        enrollmentRepository.deleteById(enrollmentRef.id.toLong())
    }
}
