package com.example.identity.core.orchestrator.admin

import com.example.identity.demo.demo_mode.DemoSurface
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The same theme switch as [LoginThemeController], without admin login, so every visitor can
 * compare the two themes. It changes only how Keycloak's pages look, never who may sign in. Its own
 * endpoint, so the admin API stays admin-only as a whole.
 */
@RestController
@DemoSurface
@RequestMapping("$DEMO_API/login-theme")
@Tag(name = "Demo: login theme", description = "Switch Keycloak's login pages between FreeMarker and Keycloakify, without admin login")
@Profile("keycloak")
class DemoLoginThemeController(private val loginThemeSwitch: LoginThemeSwitch) {

    @GetMapping
    @Operation(summary = "Current login theme")
    fun get(): LoginThemeState = LoginThemeState(loginThemeSwitch.current())

    @PutMapping
    @Operation(
        summary = "Set the login theme for every visitor",
        description = "Same as the admin endpoint: Keycloak first, then remembered - if Keycloak refuses, nothing changes."
    )
    fun put(@RequestBody request: LoginThemeState) {
        loginThemeSwitch.switchTo(request.theme)
    }
}
