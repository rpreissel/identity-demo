package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.kc.Loa1Login
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class Loa1LoginState(val login: Loa1Login)

/**
 * Runtime switch of the web channel's `loa1` between Keycloak's password and the orchestrator's
 * method selection (ADR-42), realm-wide. Only under the `keycloak` profile.
 */
@RestController
@RequestMapping("$ADMIN_API/loa1-login")
@Tag(name = "Admin: loa1 login", description = "Switch the web channel's loa1 between Keycloak's password and the orchestrator's method selection")
@Profile("keycloak")
class Loa1LoginController(private val loa1LoginSwitch: Loa1LoginSwitch) {

    @GetMapping
    @Operation(summary = "What loa1 asks for")
    fun get(): Loa1LoginState = Loa1LoginState(loa1LoginSwitch.current())

    @PutMapping
    @Operation(
        summary = "Set what loa1 asks for",
        description = "Sets the browser flow's loa1 branch in Keycloak first, then remembers it - if Keycloak refuses, nothing changes."
    )
    fun put(@RequestBody request: Loa1LoginState) {
        loa1LoginSwitch.switchTo(request.login)
    }
}
