package com.example.identity.tools.ident_kvnr

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_api only
 * (docs/03-tool-architektur.md #2, docs/04-orchestrierung.md #5). It does not depend on `ident_eid`:
 * the link is the account's claims plus this tool's `requires`, so any future attestation
 * procedure feeds it unchanged (ADR-18).
 */
@ApplicationModule(id = "ident_kvnr", allowedDependencies = ["tool_api", "texts"])
internal class ModuleMetadata
