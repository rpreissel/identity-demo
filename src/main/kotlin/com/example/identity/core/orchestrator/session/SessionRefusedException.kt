package com.example.identity.core.orchestrator.session

/**
 * Keycloak did not open a session for this login. A channel is `AUTHENTICATED` only together with
 * its session (ADR-43), so the transition that asked for it does not happen.
 */
class SessionRefusedException(message: String) : RuntimeException(message)
