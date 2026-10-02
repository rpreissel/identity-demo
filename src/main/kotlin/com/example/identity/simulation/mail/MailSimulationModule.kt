package com.example.identity.simulation.mail

import org.springframework.modulith.ApplicationModule

/**
 * The simulated mail server - a stand-in for a *foreign* system, like `kobil`: it knows
 * nothing of ours, not even `texts`. `auth_email` calls [MailServer] directly (named exception in
 * docs/08-projektrahmen.md M-3, same pattern as ADR-31).
 */
@ApplicationModule(id = "mail", allowedDependencies = ["demo_mode"])
internal class MailSimulationModule
