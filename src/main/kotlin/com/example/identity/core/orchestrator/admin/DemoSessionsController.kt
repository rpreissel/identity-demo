package com.example.identity.core.orchestrator.admin

import com.example.identity.demo.demo_mode.DemoSurface
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The session overview and the reset without admin login, for the welcome page: before anyone
 * resets the demo, it shows who is still using it. The same services as the admin endpoints.
 */
@RestController
@DemoSurface
@RequestMapping(DEMO_API)
@Tag(name = "Demo: sessions and reset", description = "Live sessions and the demo reset, without admin login")
class DemoSessionsController(
    private val activeSessions: ActiveSessions,
    private val demoReset: DemoReset,
) {

    @GetMapping("sessions")
    @Operation(summary = "Live channels and open Keycloak sessions", description = ActiveSessions.DESCRIPTION)
    fun demoSessions(): ActiveSessionsView = activeSessions.report()

    @PostMapping("reset")
    @Operation(summary = "Put the demo back to its start", description = DemoReset.DESCRIPTION)
    fun resetDemo(): DemoResetResult = demoReset.reset()
}
