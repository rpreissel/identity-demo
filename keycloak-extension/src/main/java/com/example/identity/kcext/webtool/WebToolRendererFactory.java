package com.example.identity.kcext.webtool;

import com.example.identity.kcext.KcText;
import org.keycloak.provider.ProviderFactory;

/**
 * One factory per orchestrator toolId; {@link #getId()} is that toolId (e.g. {@code "auth-password"}),
 * so Keycloak's by-id provider lookup serves as the registry.
 */
public interface WebToolRendererFactory extends ProviderFactory<WebToolRenderer> {

    /** Display title, shown as this tool's form header and as its label on the method-select page. */
    KcText title();

    /** Short hint, shown under the title. */
    KcText hint();

    /**
     * The page this tool renders, known before rendering, so the page gets exactly the texts its
     * Keycloakify component uses (ADR-41). {@code null} for a tool that completes on activation.
     */
    String template();
}
