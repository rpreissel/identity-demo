package com.example.identity.kcext.bootstrap;

import org.junit.jupiter.api.Test;

import static com.example.identity.kcext.bootstrap.MigrationClientBootstrapFactory.requireTrustedJwksUrl;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Whoever answers at the migration client's jwks.url signs in with create-realm: only over TLS or loopback. */
class MigrationClientJwksUrlTest {

    @Test
    void httpsIsAccepted() {
        assertDoesNotThrow(() -> requireTrustedJwksUrl("https://orchestrator.example/jwks.json", false));
    }

    @Test
    void httpToLoopbackIsAccepted() {
        assertDoesNotThrow(() -> requireTrustedJwksUrl("http://localhost:8080/jwks.json", false));
        assertDoesNotThrow(() -> requireTrustedJwksUrl("http://127.0.0.1:8080/jwks.json", false));
    }

    @Test
    void httpToAnotherHostIsRefused() {
        assertThrows(IllegalStateException.class, () -> requireTrustedJwksUrl("http://orchestrator:8080/jwks.json", false));
    }

    @Test
    void httpToAnotherHostNeedsTheExplicitOption() {
        assertDoesNotThrow(() -> requireTrustedJwksUrl("http://host.containers.internal:8080/jwks.json", true));
    }
}
