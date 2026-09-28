package com.example.identity.tools.auth_password.internal.authpassword
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository

import com.example.identity.tools.auth_password.AuthPasswordDescriptor
import com.example.identity.tools.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-password, device-linked case (docs/06-ablaeufe.md #3). [start]'s [enrollmentRef] is
 * resolved by the controller, since this module never reads `account`. Only the password is asked
 * for. The input decision lives in [AuthPasswordFlow].
 */
@Component
class AuthPasswordToolHandler(
    private val descriptor: AuthPasswordDescriptor,
    private val toolDataRepository: AuthPasswordToolSessionRepository,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) {

    @Transactional
    fun start(toolSessionId: UUID, enrollmentRef: EnrollmentRef): ToolOutcome {
        if (enrollmentRef.type != PASSWORD_ENROLLMENT_TYPE) {
            throw UnresolvableReferenceException(Text("Unerwarteter Enrollment-Typ"), "type=${enrollmentRef.type}")
        }
        val enrollmentId = enrollmentRef.id.toLongOrNull()
            ?: throw UnresolvableReferenceException(Text("Ungueltige Enrollment-Referenz"), "id=${enrollmentRef.id}")
        if (!enrollmentRepository.existsById(enrollmentId)) {
            throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "id=${enrollmentRef.id}")
        }

        toolDataRepository.save(
            AuthPasswordToolSession(
                toolSessionId = toolSessionId,
                enrollmentRefId = enrollmentRef.id
            )
        )
        return outcomeFor()
    }

    /** Called directly by AuthPasswordToolController (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, password: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-password tool session: $toolSessionId" }

        return when (val decision = AuthPasswordFlow.decide(AuthPasswordInput(password))) {
            AuthPasswordDecision.Unchanged -> outcomeFor()
            is AuthPasswordDecision.Check -> {
                // Gone during the tool session (removed on another channel): no wrong guess, nothing to count.
                val enrollment = data.enrollmentRefId?.toLongOrNull()?.let { enrollmentRepository.findByIdOrNull(it) }
                    ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "toolSession=$toolSessionId")

                if (PasswordHasher.matches(decision.password, enrollment.passwordHash)) {
                    PasswordHasher.upgrade(enrollment, decision.password)
                    ToolOutcome.Completed.Authenticated(
                        amr = listOf(descriptor.method),
                        achievedAcr = descriptor.maxAcr,
                        factorTypes = descriptor.factorTypes
                    )
                } else {
                    ToolOutcome.Failed.IdentifiedAuth(Text("Passwort ungueltig"))
                }
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-password tool session: $toolSessionId" }
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        val (step, fields) = AuthPasswordFlow.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = AuthPasswordFlow.demo())
    }
}
