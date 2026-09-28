package com.example.identity.simulation.personenverzeichnis

import org.springframework.modulith.ApplicationModule

/**
 * External master-data stub. The orchestrator calls in, never the other way round.
 * `Personenverzeichnis` implements `tool_api.PersonDirectory` (docs/04-orchestrierung.md #5).
 * A second face is [Freischaltcodes]: the register issues them, so `ident_fsc` asks it directly
 * whether one is valid (ADR-31).
 */
@ApplicationModule(
    id = "personenverzeichnis",
    allowedDependencies = ["tool_api", "texts", "demo_mode"]
)
internal class ModuleMetadata
