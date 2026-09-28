package com.example.identity.tools.auth_device.internal

import com.example.identity.tools.auth_device.DEVICE_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.credentials.EnrollmentCleanup
import com.example.identity.contract.tool_api.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class DeviceEnrollmentCleanup(
    private val enrollmentRepository: DeviceEnrollmentRepository
) : EnrollmentCleanup {
    override val enrollmentType = DEVICE_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        enrollmentRepository.deleteById(enrollmentRef.id.toLong())
    }
}
