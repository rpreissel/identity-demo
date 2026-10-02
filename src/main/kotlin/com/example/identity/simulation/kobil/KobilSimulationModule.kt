package com.example.identity.simulation.kobil

import org.springframework.modulith.ApplicationModule

/**
 * The simulated KOBIL backend, a stand-in for a foreign system. It depends on nothing of ours and
 * knows nothing of the journey; otherwise the demo would stop demonstrating anything. Two faces:
 * HTTP for the app (`kobil.api.v1`, the MC SDK's counterpart) and [KobilSsms] for our backend.
 */
@ApplicationModule(id = "kobil", allowedDependencies = ["texts", "demo_mode"])
internal class KobilSimulationModule
