package com.example.identity.tools.auth_password.internal.authpassword
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository

import com.example.identity.tools.auth_password.internal.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import org.springframework.data.repository.findByIdOrNull
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
        if (enrollmentRef.type != PASSWORD_ENROLLMENT_TYPE) {
            throw UnresolvableReferenceException(Text("Unerwarteter Enrollment-Typ"), "type=${enrollmentRef.type}")
        }
        val enrollmentId = enrollmentRef.id.toLongOrNull()
            ?: throw UnresolvableReferenceException(Text("Ungueltige Enrollment-Referenz"), "id=${enrollmentRef.id}")
        if (!enrollmentRepository.existsById(enrollmentId)) {
            throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "id=${enrollmentRef.id}")
        }

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
                // Gone during the tool session (removed on another channel): no wrong guess, nothing to count.
                val enrollment = data.enrollmentRefId?.toLongOrNull()?.let { enrollmentRepository.findByIdOrNull(it) }
                    ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "toolSession=$toolSessionId")

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
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.require<AuthPasswordToolSession>(toolSessionId)
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        val (step, fields) = AuthPasswordFlow.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = AuthPasswordFlow.demo())
    }
}
