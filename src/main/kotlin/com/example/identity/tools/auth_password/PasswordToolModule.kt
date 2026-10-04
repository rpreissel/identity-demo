package com.example.identity.tools.auth_password

import com.example.identity.tools.auth_password.api.v1.PasswordStepData
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.FactorType.KNOWLEDGE
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.ClaimTrust
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

/**
 * "This account holds a password credential", established by `enroll-password` and retracted with
 * it. A fact about the credentials, not the person. It lets another method depend on the password
 * with a plain `requires` (ADR-24). Its value is the constant [PASSWORD_EXISTS_MARKER].
 */
internal val PASSWORD_EXISTS = AttributeType.ownedByMethod("password_exists")

/** The only value of a [PASSWORD_EXISTS] claim; the claim itself is the statement. */
internal const val PASSWORD_EXISTS_MARKER = "true"

internal const val ENROLL_PASSWORD_TOOL_ID = "enroll-password"
internal const val AUTH_PASSWORD_TOOL_ID = "auth-password"
internal const val AUTH_PASSWORD_LOOKUP_TOOL_ID = "auth-password-lookup"

/**
 * The password procedure: `enroll-password`, `auth-password`, `auth-password-lookup`
 * (docs/03-tool-architektur.md #1). No identifier field: the account's confirmed email is the
 * identifier, so enrolling only asks for the password itself, and only once that email is proven.
 * The enrollment states that the account has a password (`password_exists`), so another method can
 * depend on it via `requires` (ADR-24); revoking the password retracts the claim, and whatever
 * required it falls with it. The name lives on [PasswordCredentialPort.METHOD] for other modules.
 */
internal val PasswordModule = toolModule(
    method = PasswordCredentialPort.METHOD,
    name = Text("Passwort"),
    proves = factors(KNOWLEDGE, upTo = AcrLevel.LOA1),
    stepData = PasswordStepData,
)

internal val EnrollPassword = PasswordModule.enroll(
    ENROLL_PASSWORD_TOOL_ID,
    versions = setOf(1),
    hint = Text("Eigenes Passwort festlegen"),
    claims = setOf(PASSWORD_EXISTS),
    requires = setOf(ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN)),
    changeable = true,
)
internal val AuthPassword = PasswordModule.login(AUTH_PASSWORD_TOOL_ID, versions = setOf(1), hint = Text("Mit dem hinterlegten Passwort"))
internal val AuthPasswordLookup = PasswordModule.lookupLogin(
    AUTH_PASSWORD_LOOKUP_TOOL_ID,
    versions = setOf(1),
    hint = Text("E-Mail-Adresse + Passwort"),
)

/**
 * A method module talks to the orchestrator through tool_api only, never to account or another
 * method module (docs/03-tool-architektur.md #2). Its controllers (`auth_password.api.v1`) reach the
 * orchestrator through `tool_api` alone (docs/04-orchestrierung.md #5).
 */
@ApplicationModule(id = "auth_password", allowedDependencies = ["tool_api", "texts"])
@Configuration
internal class PasswordToolModule {
    @Bean
    fun passwordModule() = PasswordModule
}
