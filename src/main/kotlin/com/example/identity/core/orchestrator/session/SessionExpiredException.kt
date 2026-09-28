package com.example.identity.core.orchestrator.session

/**
 * The login behind a channel is over: the refresh window lapsed or Keycloak refused the refresh.
 * Never answered with new tokens, which would keep a channel alive without new proof. The caller
 * ends the channel; the client signs in again.
 */
class SessionExpiredException(message: String) : RuntimeException(message)
