package com.example.identity.core.orchestrator

import org.springframework.modulith.ApplicationModule

/**
 * The only module that may reference the others (docs/08-projektrahmen.md #3), so its allowed set
 * is written down here.
 *
 * Where to start reading (docs/adr/ADR-040-fachkern-im-paket-domain.md): everything in `domain` is
 * the rules, free of any framework -
 * 1. `domain.journey.Journey` - what a journey is: where it may start and how it decides its next
 *    step (`Transition`, `Action`); `domain.Journeys` lists them all;
 * 2. one journey with its states, e.g. `domain.journeys.RegisterJourney` with
 *    `domain.journey.state.RegisterState`;
 * 3. `domain.policy.AuthPolicy` and `DefaultAuthPolicy` - which level the evidence is worth;
 * 4. `domain.journey.AccountRules` and `CredentialRules` - what the acting phase may do to accounts.
 * Everything outside `domain` (journey, channel, session, kc, api) is how those rules are run,
 * stored and served.
 *
 * This type serves as the package descriptor: a `package-info.kt` with only
 * `@file:ApplicationModule` compiles to no class, and the boundary would silently vanish.
 */
@ApplicationModule(
    id = "orchestrator",
    allowedDependencies = ["tool_api", "account", "texts", "demo_mode"]
)
internal class OrchestratorModule
