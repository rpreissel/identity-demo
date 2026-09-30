package com.example.identity.tools.auth_password.api.v1

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.InvalidStateException
import com.example.identity.contract.tool_api.KeycloakToolCalls
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.PASSWORD_EXISTS_MARKER
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.envelope.API_V1
import com.example.identity.tools.auth_password.AuthPasswordDescriptor
import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class MgmtPasswordVerifyRequest(val password: String? = null)
data class MgmtPasswordVerifyResponse(val valid: Boolean)
data class MgmtPasswordSetRequest(val newPassword: String? = null)

/**
 * Keycloak's native password form, checked and replaced for an account it already knows, with no
 * channel and no journey (docs/05-api.md Abschnitt 3). The caller is Keycloak's peer-auth assertion
 * for the path's account; the result is booked by the orchestrator ([KeycloakToolCalls]).
 */
@RestController
@Tag(name = "KC password management", description = "Stateless password verify/set for Keycloak's native credential")
@SecurityRequirement(name = "kc-peer-auth")
class MgmtPasswordController(
    private val keycloakToolCalls: KeycloakToolCalls,
    private val lockouts: Lockouts,
    private val accountDirectory: AccountDirectory,
    private val passwordCredentialPort: PasswordCredentialPort,
) {

    // The Authorization header carries the assertion; the @BindingKey resolver reads it, the
    // @Parameter keeps it in the published contract.
    @PostMapping("$API_V1/tools/auth-password/mgmt/{accountId}")
    @Operation(summary = "Verify a candidate password against the account's stored credential")
    @Parameter(name = "Authorization", `in` = ParameterIn.HEADER, required = false, schema = Schema(type = "string"))
    fun verify(
        @PathVariable accountId: AccountId,
        @BindingKey(keycloakOnly = true) bindingKeyRef: String,
        @RequestBody(required = false) request: MgmtPasswordVerifyRequest?,
    ): ResponseEntity<MgmtPasswordVerifyResponse> {
        keycloakToolCalls.requireKeycloakFor(accountId, bindingKeyRef)
        // The same lockout as auth-password: it is the same password to guess. The hash is computed
        // even when locked, so the timing does not reveal the lock.
        val locked = lockouts.isLockedOut(accountId)
        val enrollmentRef = accountDirectory.activeEnrollment(accountId, AuthPasswordDescriptor.method)
        val matches = passwordCredentialPort.verify(enrollmentRef, request?.password.orEmpty())
        if (!locked) {
            keycloakToolCalls.apply(
                accountId, AuthPasswordDescriptor,
                if (matches) ToolOutcome.Completed.Authenticated(amr = listOf(AuthPasswordDescriptor.method))
                else ToolOutcome.Failed.KnownAccountAuth(Text("Passwort ungueltig"))
            )
        }
        return ResponseEntity.ok(MgmtPasswordVerifyResponse(matches && !locked))
    }

    @PostMapping("$API_V1/tools/enroll-password/mgmt/{accountId}")
    @Operation(summary = "Replace the account's password credential with a new one")
    @Parameter(name = "Authorization", `in` = ParameterIn.HEADER, required = false, schema = Schema(type = "string"))
    fun set(
        @PathVariable accountId: AccountId,
        @BindingKey(keycloakOnly = true) bindingKeyRef: String,
        @RequestBody request: MgmtPasswordSetRequest,
    ): ResponseEntity<Void> {
        keycloakToolCalls.requireKeycloakFor(accountId, bindingKeyRef)
        // Only replaces an existing password. Keycloak's admin reset must not give an account a new
        // method; that is enroll-password's job behind the MANAGE check.
        if (accountDirectory.activeEnrollment(accountId, EnrollPasswordDescriptor.method) == null) {
            throw InvalidStateException(Text("Für dieses Konto ist kein Passwort eingerichtet"))
        }
        val newPassword = requireNotNull(request.newPassword) { "newPassword is required" }
        val enrollmentRef: EnrollmentRef = passwordCredentialPort.setNew(newPassword)
        keycloakToolCalls.apply(
            accountId, EnrollPasswordDescriptor,
            ToolOutcome.Completed.Enrolled(
                enrollmentRef = enrollmentRef,
                claims = listOf(Claim(AttributeType.PASSWORD_EXISTS, PASSWORD_EXISTS_MARKER, ClaimSource(EnrollPasswordDescriptor.toolId.value))),
                instanceDetails = mapOf("source" to "kc-native"),
            )
        )
        return ResponseEntity.noContent().build()
    }
}
