package com.example.identity.core.account

import org.springframework.modulith.ApplicationModule

/**
 * The account module and method modules are independent; they share only the tool_api contracts.
 * `AccountService` and `AccountProfile` are what other modules see; `domain` holds the rules,
 * `application` the services applying them, `infrastructure` entities and repositories (ADR-40).
 * `AccountService` implements `tool_api.AccountDirectory`, so a tool controller never depends on
 * `account` itself (docs/04-orchestrierung.md #8).
 */
@ApplicationModule(id = "account", allowedDependencies = ["tool_api", "texts"])
internal class AccountModule
