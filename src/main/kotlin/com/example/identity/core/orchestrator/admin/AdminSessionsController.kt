package com.example.identity.core.orchestrator.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The session overview ([ActiveSessions]) for the operator, under [ADMIN_API] and its login. */
@RestController
@RequestMapping("$ADMIN_API/sessions")
@Tag(name = "Admin: sessions", description = "Live channels and open Keycloak sessions")
class AdminSessionsController(private val activeSessions: ActiveSessions) {

    @GetMapping
    @Operation(summary = "Live channels and open Keycloak sessions", description = ActiveSessions.DESCRIPTION)
    fun adminSessions(): ActiveSessionsView = activeSessions.report()
}
