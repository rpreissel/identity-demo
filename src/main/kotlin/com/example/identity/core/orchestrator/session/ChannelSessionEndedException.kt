package com.example.identity.core.orchestrator.session

/**
 * The channel's Keycloak session ended while the channel was in use, and the channel ended with it
 * (ADR-43). Unlike other failures this must commit, so the transactional services that a journey
 * interaction passes name it in `noRollbackFor`. Answered with `410`.
 */
class ChannelSessionEndedException(message: String) : RuntimeException(message)
