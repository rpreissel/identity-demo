package com.example.identity.kcext.login;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ApiFailure} decides how {@code OrchestratorAuthenticator.action} answers an orchestrator
 * error. A 5xx is an outage, never a rejection the user caused.
 */
class ApiFailureTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422, 429, 499})
    void clientErrorRejectsTheRequest(int status) {
        assertEquals(ApiFailure.REJECTED, ApiFailure.of(status));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 302})
    void serverErrorMeansUnavailable(int status) {
        assertEquals(ApiFailure.UNAVAILABLE, ApiFailure.of(status));
    }
}
