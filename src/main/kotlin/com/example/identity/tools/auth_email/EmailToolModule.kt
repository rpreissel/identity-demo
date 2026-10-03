package com.example.identity.tools.auth_email

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.FactorType.KNOWLEDGE
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.ClaimTrust
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

internal const val CONFIRM_EMAIL_TOOL_ID = "confirm-email"
internal const val ENROLL_EMAIL_TOOL_ID = "enroll-email"
internal const val AUTH_EMAIL_TOOL_ID = "auth-email"
internal const val AUTH_EMAIL_LOOKUP_TOOL_ID = "auth-email-lookup"

/**
 * The email procedure (docs/03-tool-architektur.md #1). Two tools rather than one (ADR-17):
 * `confirm-email` proves the subject controls an address and nothing else, so the account keeps it
 * as its EMAIL anchor and no method appears; a confirmed address is no factor and reports no `amr`.
 * `enroll-email` turns an already confirmed address into a login method, one shot without a code;
 * it reports no `amr` either, since nothing was proven in that run.
 */
internal val EmailModule = toolModule(
    method = "email",
    proves = factors(KNOWLEDGE, upTo = AcrLevel.LOA1),
)

internal val ConfirmEmail = EmailModule.confirm(CONFIRM_EMAIL_TOOL_ID, claims = setOf(AttributeType.EMAIL))
internal val EnrollEmail = EmailModule.enroll(
    ENROLL_EMAIL_TOOL_ID,
    requires = setOf(ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN)),
    withoutUserStep = Text("Ihre bereits bestätigte E-Mail-Adresse wird sofort zum Anmeldeverfahren. Einen Code brauchen Sie dafür nicht."),
)
internal val AuthEmail = EmailModule.login(AUTH_EMAIL_TOOL_ID)
internal val AuthEmailLookup = EmailModule.lookupLogin(AUTH_EMAIL_LOOKUP_TOOL_ID)

/**
 * The confirmed email is the account's identifier, not a swappable credential
 * (docs/02-domaenenmodell.md #6). It is recorded as an EMAIL claim, which consolidates the
 * `account.anchor` row; lookups by email go through `AccountDirectory` without this module.
 * Like every method module it reaches the orchestrator through tool_api only
 * (docs/03-tool-architektur.md #2, docs/04-orchestrierung.md #5).
 */
@ApplicationModule(id = "auth_email", allowedDependencies = ["tool_api", "texts", "mail"])
@Configuration
internal class EmailToolModule {
    @Bean
    fun emailModule() = EmailModule
}
