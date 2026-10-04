package com.example.identity.core.orchestrator.domain

/**
 * Who proved a method: the orchestrator's own tool, or a native Keycloak authenticator
 * (docs/05-api.md Abschnitt 3b).
 *
 * Domain vocabulary, not a persistence detail: the policy judges evidence by its origin and must
 * not depend on the session package that stores it.
 */
object AmrSource {
    const val ORCHESTRATOR = "orchestrator"
    const val KEYCLOAK = "kc"
}
