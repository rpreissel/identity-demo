package com.example.identity.tools.auth_kobil.internal

import com.example.identity.simulation.kobil.KobilSsms
import com.example.identity.simulation.kobil.KobilUserRef
import com.example.identity.contract.tool_api.credentials.EnrollmentCleanup
import com.example.identity.contract.tool_api.EnrollmentRef
import org.springframework.stereotype.Component

/**
 * Revoking a KOBIL method also deletes the activation at the provider, or a device would stay bound
 * at KOBIL. That call is a non-transactional external effect (docs/07-betrieb.md #2); our row goes
 * either way.
 */
@Component
class KobilEnrollmentCleanup(
    private val enrollmentRepository: KobilEnrollmentRepository,
    private val ssms: KobilSsms,
) : EnrollmentCleanup {
    override val enrollmentType = KOBIL_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        val id = enrollmentRef.id.toLong()
        enrollmentRepository.findById(id).ifPresent { enrollment ->
            ssms.deprovisionUser(KobilUserRef(enrollment.kobilTenantId, enrollment.kobilUserId))
        }
        enrollmentRepository.deleteById(id)
    }
}
