package com.example.identity.tools.auth_password.api.v1

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.KeycloakToolCalls
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.tools.auth_password.PasswordModule
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.envelope.API_V1
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class MgmtPasswordVerifyRequest(val password: String? = null)
data class MgmtPasswordVerifyResponse(val valid: Boolean)

/**
 * Keycloak's native password form, checked for an account it already knows, with no channel and
 * no journey (docs/05-api.md Abschnitt 3b). Keycloak never changes a password: that runs through the
 * orchestrator's method management, behind its level check. The caller is Keycloak's peer-auth assertion
 * for the path's account; the result is booked by the orchestrator ([KeycloakToolCalls]).
 */
@RestController
@Tag(name = "Keycloak password management", description = "Stateless password verification for Keycloak's native credential")
@SecurityRequirement(name = "kc-peer-auth")
class MgmtPasswordController(
    private val keycloakToolCalls: KeycloakToolCalls,
    private val lockouts: Lockouts,
    private val accountDirectory: AccountDirectory,
    private val passwordCredentialPort: PasswordCredentialPort,
) {

    // The Authorization header carries the assertion; the @BindingKey resolver reads it, the
    // @Parameter keeps it in the published contract.
    // Under /kc like every endpoint only Keycloak calls: not part of the frozen app contract (ADR-50).
    @PostMapping("$API_V1/kc/accounts/{accountId}/password-checks")
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
        val enrollmentRef = accountDirectory.activeEnrollment(accountId, PasswordModule.method)
        val matches = passwordCredentialPort.verify(enrollmentRef, request?.password.orEmpty())
        if (!locked) {
            keycloakToolCalls.apply(
                accountId, PasswordModule,
                if (matches) ToolOutcome.Completed.Authenticated()
                else ToolOutcome.Failed.KnownAccountAuth(Text("Passwort ungueltig"))
            )
        }
        return ResponseEntity.ok(MgmtPasswordVerifyResponse(matches && !locked))
    }
}
