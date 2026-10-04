package com.example.identity.simulation.nect

import org.springframework.modulith.ApplicationModule

/**
 * The simulated identification service Nect, a stand-in for a foreign system like `kobil`.
 * Two faces: [NectIdent] for our backend (`ident_nect`), and `/mock-nect/...` for its jump page
 * (`/nect/`), where the user picks eID, ePass or EUDI wallet (docs/verfahren/nect.md).
 */
@ApplicationModule(id = "nect", allowedDependencies = ["texts", "demo_mode"])
internal class NectSimulationModule
