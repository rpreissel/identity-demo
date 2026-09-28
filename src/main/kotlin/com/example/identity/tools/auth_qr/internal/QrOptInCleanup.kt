package com.example.identity.tools.auth_qr.internal

import com.example.identity.tools.auth_qr.QR_OPTIN_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.credentials.EnrollmentCleanup
import com.example.identity.contract.tool_api.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class QrOptInCleanup(
    private val qrOptInRepository: QrOptInRepository
) : EnrollmentCleanup {
    override val enrollmentType = QR_OPTIN_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        qrOptInRepository.deleteById(enrollmentRef.id.toLong())
    }
}
