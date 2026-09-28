package com.example.identity.tools.auth_password

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_api only, never to account or
 * another method module (docs/03-tool-architektur.md #2). Its controllers (`auth_password.api.v1`) reach the
 * orchestrator through `tool_api` alone (docs/04-orchestrierung.md #5).
 */
@ApplicationModule(id = "auth_password", allowedDependencies = ["tool_api", "texts"])
internal class ModuleMetadata
