package com.example.identity.tools.auth_invite

import org.springframework.modulith.ApplicationModule

/**
 * Process access by one-time password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Like every
 * method module it talks to the orchestrator through tool_api only; the person register and the
 * register's invitations are reached over ports ([com.example.identity.contract.tool_api.directory.PersonDirectory],
 * [com.example.identity.contract.tool_api.directory.Invitations]).
 */
@ApplicationModule(id = "auth_invite", allowedDependencies = ["tool_api", "texts"])
internal class ModuleMetadata
