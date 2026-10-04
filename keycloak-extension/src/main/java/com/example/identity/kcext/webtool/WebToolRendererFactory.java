package com.example.identity.kcext.webtool;

import org.keycloak.provider.ProviderFactory;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One factory per orchestrator toolId; {@link #getId()} is that toolId (e.g. {@code "auth-password"}),
 * so Keycloak's by-id provider lookup serves as the registry. Name and hint are not its own: the
 * orchestrator's tool catalog has them ({@code OrchestratorToolCatalog}).
 */
public interface WebToolRendererFactory extends ProviderFactory<WebToolRenderer> {

    /**
     * The one version of the tool's contract this renderer speaks (ADR-51): declared as
     * {@code <toolId>@<version>} and called under {@code /tools/api/<toolId>/v<version>}.
     */
    int version();

    /**
     * The page this tool renders, known before rendering, so the page gets exactly the texts its
     * Keycloakify component uses (ADR-41). {@code null} for a tool that completes on activation.
     */
    String template();

    /**
     * What this tool needs to hear when it is activated, sent as the body of
     * {@code POST /tools/api/{toolId}/v{version}}; nothing for most tools. {@code actionUrl} yields Keycloak's
     * action URL of the running step, evaluated only when asked for: a tool that sends the user
     * away names it as the address to come back to (docs/adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md).
     */
    default Map<String, String> activationFields(Supplier<String> actionUrl) {
        return Map.of();
    }

    /**
     * What this tool needs to hear with a posted step besides the form's own {@code field}s;
     * nothing for most tools. Keycloak's action code is single-use, so a tool that sends the user
     * away again names a fresh action URL here, not the one from its activation.
     */
    default Map<String, String> actionFields(Function<String, String> field, Supplier<String> actionUrl) {
        return Map.of();
    }
}
