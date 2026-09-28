package com.example.identity.simulation.sms

import org.springframework.modulith.ApplicationModule

/**
 * The simulated SMS provider - a stand-in for a *foreign* system, like `kobil`: it knows
 * nothing of ours, not even `texts`. `auth_sms` calls [SmsGateway] directly (named exception in
 * docs/08-projektrahmen.md M-3, same pattern as ADR-31).
 */
@ApplicationModule(id = "sms", allowedDependencies = ["demo_mode"])
internal class ModuleMetadata
