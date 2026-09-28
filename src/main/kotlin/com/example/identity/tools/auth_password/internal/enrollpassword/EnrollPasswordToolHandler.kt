package com.example.identity.tools.auth_password.internal.enrollpassword
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.PasswordPolicy
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollment

import com.example.identity.tools.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.claims.PASSWORD_EXISTS_MARKER
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * toolId=enroll-password: registers a password as a knowledge factor (docs/06-ablaeufe.md #4). A
 * chosen password is self-verifying, so one PATCH completes it. The input decision lives in
 * [EnrollPasswordFlow].
 */
@Component
class EnrollPasswordToolHandler(
    private val descriptor: EnrollPasswordDescriptor,
    private val toolDataRepository: EnrollPasswordToolSessionRepository,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository,
    private val clock: Clock
) {

    /** Called directly by EnrollPasswordToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollPasswordToolSession(toolSessionId = toolSessionId, createdAt = clock.instant()))
        return outcomeFor()
    }

    /** Called directly by EnrollPasswordToolController (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, password: String?): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-password tool session: $toolSessionId" }

        return when (val decision = EnrollPasswordFlow.decide(EnrollPasswordInput(password))) {
            EnrollPasswordDecision.Unchanged -> outcomeFor()
            is EnrollPasswordDecision.Rejected ->
                PasswordPolicy.reject(decision.rejection)

            is EnrollPasswordDecision.Enroll -> {
                val enrollment = enrollmentRepository.save(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(decision.password), createdAt = clock.instant()))
                ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(type = PASSWORD_ENROLLMENT_TYPE, id = enrollment.id.toString()),
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    // Not the password or a digest: the claim states that one exists, so a
                    // dependent method can require it (EnrollPasswordDescriptor).
                    claims = listOf(
                        Claim(
                            attributeType = AttributeType.PASSWORD_EXISTS,
                            value = PASSWORD_EXISTS_MARKER,
                            source = ClaimSource.of(descriptor.toolId),
                            establishedAcr = descriptor.maxAcr
                        )
                    )
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-password tool session: $toolSessionId" }
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        val (step, fields) = EnrollPasswordFlow.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = EnrollPasswordFlow.demo())
    }
}
