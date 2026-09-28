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
 * The same switch as [Loa1LoginController], without admin login, so every visitor of the website
 * can try both starts of a sign-in. Either way the orchestrator's policy decides what counts as
 * `loa1`; the switch only picks who asks.
 */
@RestController
@DemoSurface
@RequestMapping("$DEMO_API/loa1-login")
@Tag(name = "Demo: loa1 login", description = "Switch the web channel's loa1 between Keycloak's password and the orchestrator's method selection, without admin login")
@Profile("keycloak")
class DemoLoa1LoginController(private val loa1LoginSwitch: Loa1LoginSwitch) {

    @GetMapping
    @Operation(summary = "What loa1 asks for")
    fun get(): Loa1LoginState = Loa1LoginState(loa1LoginSwitch.current())

    @PutMapping
    @Operation(
        summary = "Set what loa1 asks for, for every visitor",
        description = "Same as the admin endpoint: Keycloak first, then remembered - if Keycloak refuses, nothing changes."
    )
    fun put(@RequestBody request: Loa1LoginState) {
        loa1LoginSwitch.switchTo(request.login)
    }
}
