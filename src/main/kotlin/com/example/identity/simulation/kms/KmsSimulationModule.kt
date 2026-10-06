package com.example.identity.simulation.kms

import org.springframework.modulith.ApplicationModule

/**
 * The simulated key management service, a stand-in for a foreign system such as Vault Transit, a
 * cloud KMS or an HSM (ADR-54). It implements the port `tool_api.kms.KeyService` for our backend
 * and adds the operator's face `kms.api.v1`, where a tester rotates keys and watches what happens.
 */
@ApplicationModule(id = "kms", allowedDependencies = ["tool_api", "demo_mode"])
internal class KmsSimulationModule
