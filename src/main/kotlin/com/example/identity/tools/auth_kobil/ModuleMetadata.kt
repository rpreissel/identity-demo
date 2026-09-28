package com.example.identity.tools.auth_kobil

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_api only
 * (docs/03-tool-architektur.md #2). The extra edge to `kobil` is the point of the module:
 * a KOBIL credential is not verified here, KOBIL asserts it and we redeem it. The declared
 * dependency makes that visible in the module graph.
 */
@ApplicationModule(
    id = "auth_kobil",
    allowedDependencies = ["tool_api", "kobil", "texts"]
)
internal class ModuleMetadata
