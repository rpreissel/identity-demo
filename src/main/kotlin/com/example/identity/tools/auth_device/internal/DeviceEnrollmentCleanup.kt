package com.example.identity.tools.auth_device.internal

import com.example.identity.contract.tool_api.credentials.RowEnrollmentCleanup
import org.springframework.stereotype.Component

@Component
class DeviceEnrollmentCleanup(repository: DeviceEnrollmentRepository) : RowEnrollmentCleanup(DEVICE_ENROLLMENT_TYPE, repository)
