package com.example.identity.core.orchestrator

import org.springframework.modulith.ApplicationModule

/**
 * The only module that may reference the others (docs/08-projektrahmen.md #3), so its allowed set
 * is written down here.
 *
 * Where to start reading (docs/adr/ADR-040-fachkern-im-paket-domain.md): everything in `domain` is
 * the rules, free of any framework -
 * 1. `domain.AuthIntent` - the goals a user can have;
 * 2. `domain.journey.IntentStrategy` - how an intent decides its next step (`Transition`, `Action`);
 * 3. one pair of state and strategy, e.g. `domain.journey.state.RegisterState` with
 *    `domain.journey.strategy.RegisterStrategy`;
 * 4. `domain.policy.AuthPolicy` and `DefaultAuthPolicy` - which level the evidence is worth;
 * 5. `domain.journey.AccountRules` and `CredentialRules` - what the acting phase may do to accounts.
 * Everything outside `domain` (journey, channel, session, kc, api) is how those rules are run,
 * stored and served.
 *
 * This type serves as the package descriptor: a `package-info.kt` with only
 * `@file:ApplicationModule` compiles to no class, and the boundary would silently vanish.
 */
@ApplicationModule(
    id = "orchestrator",
    allowedDependencies = ["tool_api", "account", "kms", "texts", "demo_mode"]
)
internal class OrchestratorModule
