package com.example.identity.core.orchestrator.domain

/**
 * The orchestrator's domain: intents, levels, journey states and transitions, and the policy that
 * prices evidence (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 *
 * This package and its subpackages depend on nothing else inside the orchestrator and use no
 * framework - no Spring, JPA, Jackson or logging. `OrchestratorArchitectureTest` keeps that true.
 */
internal object DomainPackageMarker
