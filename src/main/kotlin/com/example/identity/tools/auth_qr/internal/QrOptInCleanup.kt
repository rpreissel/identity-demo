package com.example.identity.tools.auth_qr.internal

import com.example.identity.contract.tool_api.credentials.RowEnrollmentCleanup
import org.springframework.stereotype.Component

@Component
class QrOptInCleanup(repository: QrOptInRepository) : RowEnrollmentCleanup(QR_OPTIN_ENROLLMENT_TYPE, repository)
