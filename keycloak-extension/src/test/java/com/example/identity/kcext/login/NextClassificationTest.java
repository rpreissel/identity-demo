package com.example.identity.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrchestratorClient.Next} classifies the orchestrator's {@code next} (docs/05-api.md #2)
 * into what {@code OrchestratorAuthenticator.handleResponse} branches on. REGISTER's identification
 * choice reports {@code step="selectIdentificationMethod"}, not {@code "selectMethod"}, and must still
 * count as a selection (docs/04-orchestrierung.md #4).
 */
class NextClassificationTest {

    @Test
    void toolStepIsClassifiedAsTool() {
        var next = new OrchestratorClient.Next("tool", "enroll-sms", null, "enroll", "session-1");
        assertTrue(next.isTool());
        assertFalse(next.isSelectMethod());
        assertFalse(next.isAuthenticated());
    }

    @Test
    void orchestratorSelectMethodStepIsClassifiedAsSelectMethod() {
        var next = new OrchestratorClient.Next("orchestrator", null, "auth", "selectMethod", null);
        assertTrue(next.isSelectMethod());
    }

    @Test
    void identifyingsOwnSelectIdentificationMethodStepIsAlsoClassifiedAsSelectMethod() {
        // Named differently from "selectMethod", but the same kind of screen.
        var next = new OrchestratorClient.Next("orchestrator", null, "registration", "selectIdentificationMethod", null);
        assertTrue(next.isSelectMethod());
    }

    @Test
    void authenticatedStepIsClassifiedAsAuthenticated() {
        var next = new OrchestratorClient.Next("orchestrator", null, "authentication", "authenticated", null);
        assertTrue(next.isAuthenticated());
        assertFalse(next.isSelectMethod());
    }

    @Test
    void fromJsonParsesEveryField() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(
                "{\"type\":\"tool\",\"toolId\":\"ident-fsc\",\"step\":\"input\",\"toolSessionId\":\"abc-123\"}"
        );
        var next = OrchestratorClient.Next.from(json);
        assertTrue(next.isTool());
        assertTrue(next.toolId().equals("ident-fsc"));
        assertTrue(next.toolSessionId().equals("abc-123"));
    }
}
