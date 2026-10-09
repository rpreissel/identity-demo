package com.example.identity.kcext.login;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Keycloak books every {@code failure()} and {@code failureChallenge()} of an authenticator on the
 * authenticated user for its brute-force protection. The orchestrator's authenticators never call
 * them: an outage, an unsigned answer or a refused answer is not the user's failed attempt, and the
 * orchestrator counts real attempts itself (docs/adr/ADR-044).
 */
class NoBruteForceBookingTest {

    private static final Path LOGIN = Path.of("src/main/java/com/example/identity/kcext/login");

    @Test
    void theOrchestratorAuthenticatorsNeverReportAFailedAttempt() throws IOException {
        for (String file : new String[]{"OrchestratorAuthenticator.java"}) {
            String source = Files.readString(LOGIN.resolve(file));
            assertFalse(source.contains(".failure("), file + " calls failure()");
            assertFalse(source.contains(".failureChallenge("), file + " calls failureChallenge()");
        }
    }
}
