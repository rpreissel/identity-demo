package com.example.identity.tools.auth_password.internal.authpassword
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.credentials.requireEnrollment
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository

import com.example.identity.tools.auth_password.internal.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-password, device-linked case (docs/verfahren/password.md). [start]'s [enrollmentRef] is
 * resolved by the controller, since this module never reads `account`. Only the password is asked
 * for. The input decision lives in [AuthPasswordFlow].
 */
@Component
class AuthPasswordToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId, enrollmentRef: EnrollmentRef): ToolOutcome {
        enrollmentRepository.requireEnrollment(enrollmentRef, PASSWORD_ENROLLMENT_TYPE)

        sessions.save(toolSessionId, AuthPasswordToolSession(enrollmentRefId = enrollmentRef.id))
        return outcomeFor()
    }

    /** Called directly by AuthPasswordToolController (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, password: String?): ToolOutcome {
        val data = sessions.require<AuthPasswordToolSession>(toolSessionId)

        return when (val decision = AuthPasswordFlow.decide(AuthPasswordInput(password))) {
            AuthPasswordDecision.Unchanged -> outcomeFor()
            is AuthPasswordDecision.Check -> {
                val enrollment = enrollmentRepository.requireEnrollment(data.enrollmentRefId, toolSessionId)

                if (PasswordHasher.matches(decision.password, enrollment.passwordHash)) {
                    PasswordHasher.upgrade(enrollment, decision.password)
                    ToolOutcome.Completed.Authenticated()
                } else {
                    ToolOutcome.Failed.KnownAccountAuth(Text("Passwort ungueltig"))
                }
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        sessions.require<AuthPasswordToolSession>(toolSessionId)
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        return AuthPasswordFlow.describe().inProgress(AuthPasswordFlow.demo())
    }
}
