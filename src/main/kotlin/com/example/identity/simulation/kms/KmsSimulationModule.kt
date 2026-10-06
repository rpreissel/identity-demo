package com.example.identity.simulation.kms

import org.springframework.modulith.ApplicationModule

/**
 * The simulated key management service, a stand-in for a foreign system such as Vault Transit, a
 * cloud KMS or an HSM (ADR-54). It depends on nothing of ours. Two faces: [KmsTransit] for our
 * backend, and `kms.api.v1` for the demo, where a tester rotates keys and watches what happens.
 */
@ApplicationModule(id = "kms", allowedDependencies = ["demo_mode"])
internal class KmsSimulationModule
