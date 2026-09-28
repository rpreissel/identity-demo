package com.example.identity.tools.ident_nect

import org.springframework.modulith.ApplicationModule

/**
 * Identification through Nect (docs/03-tool-architektur.md, ident-nect). Besides tool_api
 * it declares one edge to the identification service itself (`nect.NectIdent`), like
 * `auth_kobil -> kobil` (ADR-31). Swapping in the real service changes that edge, not the tool.
 */
@ApplicationModule(
    id = "ident_nect",
    allowedDependencies = ["tool_api", "nect", "texts"]
)
internal class ModuleMetadata
