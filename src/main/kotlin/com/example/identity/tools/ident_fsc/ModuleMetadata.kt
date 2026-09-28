package com.example.identity.tools.ident_fsc

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_api only, never to account or
 * another method module (docs/03-tool-architektur.md #2). Its controllers (`ident_fsc.api.v1`) reach the
 * orchestrator through `tool_api` alone (docs/04-orchestrierung.md #5).
 *
 * The register is reached over ports only ([com.example.identity.contract.tool_api.directory.PersonDirectory],
 * [com.example.identity.contract.tool_api.directory.ActivationCodes], ADR-31). Its own classes speak its language.
 */
@ApplicationModule(id = "ident_fsc", allowedDependencies = ["tool_api", "texts"])
internal class ModuleMetadata
