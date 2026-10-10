package com.example.identity.tools.auth_sms.internal

import com.example.identity.contract.tool_api.credentials.RowEnrollmentCleanup
import org.springframework.stereotype.Component

@Component
class AuthSmsEnrollmentCleanup(repository: AuthSmsEnrollmentRepository) : RowEnrollmentCleanup(SMS_ENROLLMENT_TYPE, repository)
