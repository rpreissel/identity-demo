package com.example.identity.core.orchestrator.domain

/**
 * Who vouches for a method: the orchestrator's own tool in this channel, or Keycloak's session, from
 * which an earlier flow run's evidence is carried over (RestoreData, docs/05-api.md Abschnitt 3b).
 * Keycloak proves nothing itself (ADR-58).
 *
 * Domain vocabulary, not a persistence detail: the policy judges evidence by its origin and must
 * not depend on the session package that stores it.
 */
object AmrSource {
    const val ORCHESTRATOR = "orchestrator"
    const val KEYCLOAK = "kc"
}
