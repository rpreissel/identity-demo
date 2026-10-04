package com.example.identity.tools.auth_password.internal.enrollpassword
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_password.PASSWORD_EXISTS
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.tools.auth_password.PasswordModule
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.PasswordPolicy
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollment

import com.example.identity.tools.auth_password.internal.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.tools.auth_password.PASSWORD_EXISTS_MARKER
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * toolId=enroll-password: registers a password as a knowledge factor (docs/verfahren/password.md). A
 * chosen password is self-verifying, so one PATCH completes it. The input decision lives in
 * [EnrollPasswordFlow].
 */
@Component
class EnrollPasswordToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository,
    private val clock: Clock
) {

    /** Called directly by EnrollPasswordToolController. [replaces]: the account already has a password. */
    @Transactional
    fun start(toolSessionId: ToolSessionId, replaces: Boolean = false): ToolOutcome {
        val data = EnrollPasswordToolSession(replaces = replaces)
        sessions.save(toolSessionId, data)
        return outcomeFor(data)
    }

    /** Called directly by EnrollPasswordToolController (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, password: String?): ToolOutcome {
        val data = sessions.require<EnrollPasswordToolSession>(toolSessionId)

        return when (val decision = EnrollPasswordFlow.decide(EnrollPasswordInput(password))) {
            EnrollPasswordDecision.Unchanged -> outcomeFor(data)
            is EnrollPasswordDecision.Rejected ->
                PasswordPolicy.reject(decision.rejection)

            is EnrollPasswordDecision.Enroll -> {
                val enrollment = enrollmentRepository.save(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(decision.password), createdAt = clock.instant()))
                ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(type = PASSWORD_ENROLLMENT_TYPE, id = enrollment.id.toString()),
                    // Not the password or a digest: the claim states that one exists, so a
                    // dependent method can require it (EnrollPasswordDescriptor).
                    claims = listOf(
                        Claim(
                            attributeType = PASSWORD_EXISTS,
                            value = PASSWORD_EXISTS_MARKER,
                            source = PasswordModule.source(ToolRole.ENROLLMENT),
                            establishedAcr = PasswordModule.maxAcr
                        )
                    )
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        return outcomeFor(sessions.require<EnrollPasswordToolSession>(toolSessionId))
    }

    private fun outcomeFor(data: EnrollPasswordToolSession): ToolOutcome.InProgress {
        val (step, fields) = EnrollPasswordFlow.describe(data.replaces)
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = EnrollPasswordFlow.demo())
    }
}
