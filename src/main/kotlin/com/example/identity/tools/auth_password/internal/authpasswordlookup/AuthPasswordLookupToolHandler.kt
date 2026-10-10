package com.example.identity.tools.auth_password.internal.authpasswordlookup
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository

import com.example.identity.tools.auth_password.internal.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-password-lookup: login without a known account (docs/04-orchestrierung.md). Takes
 * email and password together in one step. The controller resolves the email and passes the result
 * into [patch]. The completeness decision lives in [AuthPasswordLookupFlow].
 */
@Component
class AuthPasswordLookupToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, AuthPasswordLookupToolSession())
        return outcomeFor()
    }

    /**
     * [accountId]/[enrollmentRef] are null for an unknown email, no active password method, or a
     * rate-limited account. The failure looks the same in every case (enumeration protection,
     * docs/04-orchestrierung.md), and costs the same: `PasswordHasher.matches` runs unconditionally.
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, email: String?, password: String?, accountId: AccountId?, enrollmentRef: EnrollmentRef?): ToolOutcome {
        sessions.require<AuthPasswordLookupToolSession>(toolSessionId)

        return when (val decision = AuthPasswordLookupFlow.decide(AuthPasswordLookupInput(email, password))) {
            is AuthPasswordLookupDecision.Incomplete -> outcomeFor(decision.missingFields)

            is AuthPasswordLookupDecision.Check -> {
                val enrollment = enrollmentRef
                    ?.takeIf { it.type == PASSWORD_ENROLLMENT_TYPE }
                    ?.id?.toLongOrNull()
                    ?.let { enrollmentRepository.findByIdOrNull(it) }

                // Unconditional, before any null check - a null enrollment hashes against a dummy
                // and costs the same. See the KDoc above.
                val passwordOk = PasswordHasher.matches(decision.password, enrollment?.passwordHash)

                if (accountId != null && enrollment != null && passwordOk) {
                    PasswordHasher.upgrade(enrollment, decision.password)
                    ToolOutcome.Completed.Authenticated(
                        subject = Subject.Account(accountId)
                    )
                } else {
                    // Naming the account here is what lets the orchestrator count this attempt;
                    // the client-facing part of the outcome stays identical for known and unknown
                    // addresses.
                    ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder Passwort ungueltig"), attempted = accountId?.let(Attempted::Account))
                }
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        sessions.require<AuthPasswordLookupToolSession>(toolSessionId)
        return outcomeFor()
    }

    private fun outcomeFor(missingFields: List<String> = listOf("email", "password")): ToolOutcome.InProgress {
        return AuthPasswordLookupFlow.describe(missingFields).inProgress(AuthPasswordLookupFlow.demo())
    }
}
