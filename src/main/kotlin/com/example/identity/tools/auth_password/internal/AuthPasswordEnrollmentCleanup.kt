package com.example.identity.tools.auth_password.internal

import com.example.identity.contract.tool_api.credentials.RowEnrollmentCleanup
import org.springframework.stereotype.Component

@Component
class AuthPasswordEnrollmentCleanup(repository: AuthPasswordEnrollmentRepository) : RowEnrollmentCleanup(PASSWORD_ENROLLMENT_TYPE, repository)
