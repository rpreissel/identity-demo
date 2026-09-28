package com.example.identity.tools.auth_email

import org.springframework.modulith.ApplicationModule

/**
 * The confirmed email is the account's identifier, not a swappable credential
 * (docs/02-domaenenmodell.md #6). It is recorded as an EMAIL claim, which consolidates the
 * `account.anchor` row; lookups by email go through `AccountDirectory` without this module.
 * Like every method module it reaches the orchestrator through tool_api only
 * (docs/03-tool-architektur.md #2, docs/04-orchestrierung.md #5).
 */
@ApplicationModule(
    id = "auth_email",
    allowedDependencies = ["tool_api", "texts", "mail"]
)
internal class ModuleMetadata
