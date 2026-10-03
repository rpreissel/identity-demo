package com.example.identity.kcext.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the orchestrator calls each tool ({@code GET .../tools/catalog}): name and hint, declared once
 * in the tool's module (docs/03-tool-architektur.md #2) and resolved in the login's language through
 * {@link OrchestratorTexts}. One copy per orchestrator, fetched again at most once a minute; if the
 * orchestrator is unreachable, the last copy stays in use, and without any a tool shows its id.
 */
public final class OrchestratorToolCatalog {

    private static final Logger LOG = Logger.getLogger(OrchestratorToolCatalog.class);
    private static final Duration REFRESH_AFTER = Duration.ofMinutes(1);

    private record Catalog(Map<String, JsonNode> byToolId, long fetchedAtNanos) {
    }

    private static final Map<String, Catalog> CATALOGS = new ConcurrentHashMap<>();

    private OrchestratorToolCatalog() {
    }

    /** What users call {@code toolId}; the id itself for a tool the catalog does not know. */
    public static String name(KeycloakSession session, String toolId) {
        JsonNode entry = entries(session).get(toolId);
        String name = entry != null ? OrchestratorTexts.resolve(session, entry.get("name")) : null;
        return name != null ? name : toolId;
    }

    /** What {@code toolId} does, in a few words; empty for a tool the catalog does not know. */
    public static String hint(KeycloakSession session, String toolId) {
        JsonNode entry = entries(session).get(toolId);
        String hint = entry != null ? OrchestratorTexts.resolve(session, entry.get("hint")) : null;
        return hint != null ? hint : "";
    }

    /** What users call {@code method}: the name of the tool that sets it up; the method itself otherwise. */
    public static String methodName(KeycloakSession session, String method) {
        for (JsonNode entry : entries(session).values()) {
            if ("ENROLLMENT".equals(entry.path("role").asText()) && method.equals(entry.path("method").asText())) {
                return name(session, entry.path("toolId").asText());
            }
        }
        return method;
    }

    private static Map<String, JsonNode> entries(KeycloakSession session) {
        OrchestratorSettings settings = OrchestratorSettings.of(session);
        String cacheKey = settings.orchestratorBaseUrl();
        Catalog cached = CATALOGS.get(cacheKey);
        if (cached != null && System.nanoTime() - cached.fetchedAtNanos() < REFRESH_AFTER.toNanos()) {
            return cached.byToolId();
        }
        try {
            Map<String, JsonNode> byToolId = new LinkedHashMap<>();
            for (JsonNode entry : settings.newClient().toolCatalog()) {
                byToolId.put(entry.path("toolId").asText(), entry);
            }
            CATALOGS.put(cacheKey, new Catalog(byToolId, System.nanoTime()));
            return byToolId;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("Orchestrator tool catalog not available: %s", e.getMessage());
            return cached != null ? cached.byToolId() : Map.of();
        }
    }
}
