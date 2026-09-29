package com.example.identity.kcext;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OrchestratorNextDispatch {

    private OrchestratorNextDispatch() {
    }

    sealed interface Outcome permits Select, Tool, Confirm, Unhandled {
    }

    record Select(List<String> options) implements Outcome {
    }

    record Tool(OrchestratorClient.Next next, boolean autoActivate) implements Outcome {
    }

    record Confirm(com.fasterxml.jackson.databind.JsonNode prompt) implements Outcome {
    }

    record Unhandled(OrchestratorClient.Next next) implements Outcome {
    }

    /**
     * next must already be non-null and non-authenticated; callers check terminal states first.
     */
    static Outcome classify(OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response) {
        if (next.isSelectMethod()) {
            return new Select(response.stepDataOptions());
        }
        if (next.isTool()) {
            return new Tool(next, next.toolSessionId() == null);
        }
        if (next.isConfirm()) {
            return new Confirm(response.stepData().get("prompt"));
        }
        return new Unhandled(next);
    }

    /**
     * The parameters Keycloak itself puts on an action URL; everything else in the query is the
     * tool's input, as if it had been posted. That is how a return from outside reaches a tool: the
     * address the user comes back to is the running step's action URL, and whoever sends them back
     * appends its own parameters ({@code ?nectCaseId=...}, docs/ideen/ident-nect.md #3).
     */
    static final Set<String> KEYCLOAK_ACTION_PARAMS = Set.of(
            "session_code", "execution", "client_id", "tab_id", "client_data", "auth_session_id", "kc_locale", "token");

    /** The form's fields plus the query's own; a name in both keeps the form's value. */
    static MultivaluedMap<String, String> withQueryParams(MultivaluedMap<String, String> form, MultivaluedMap<String, String> query) {
        MultivaluedMap<String, String> merged = new MultivaluedHashMap<>();
        if (query != null) {
            query.forEach((key, values) -> {
                if (!KEYCLOAK_ACTION_PARAMS.contains(key)) merged.put(key, new ArrayList<>(values));
            });
        }
        if (form != null) form.forEach((key, values) -> merged.put(key, new ArrayList<>(values)));
        return merged;
    }

    static OrchestratorClient.ChannelResponse dispatchToolAction(
            OrchestratorClient client, String channelSessionId, String toolId, String toolSessionId,
            MultivaluedMap<String, String> form
    ) throws IOException, InterruptedException {
        // "Zurück" goes back to the selection with this tool still on it; "Abbrechen" declines it.
        if ("true".equals(form.getFirst("orchestrator_back"))) {
            return client.backFromTool(channelSessionId, toolSessionId, toolId);
        }
        if ("true".equals(form.getFirst("orchestrator_abandon"))) {
            return client.abandonTool(channelSessionId, toolSessionId, toolId);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        form.forEach((key, values) -> {
            if (!key.startsWith("orchestrator_") && !values.isEmpty()) fields.put(key, values.get(0));
        });
        return client.patchTool(channelSessionId, toolSessionId, toolId, fields);
    }
}
