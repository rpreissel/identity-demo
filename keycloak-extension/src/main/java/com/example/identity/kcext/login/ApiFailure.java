package com.example.identity.kcext.login;

/**
 * How a login step answers an orchestrator error status. A 4xx rejects this request and carries a
 * text for the user; anything else means the orchestrator is not available right now. Neither is
 * the user's failed attempt.
 */
enum ApiFailure {
    REJECTED,
    UNAVAILABLE;

    static ApiFailure of(int status) {
        return status >= 400 && status < 500 ? REJECTED : UNAVAILABLE;
    }
}
