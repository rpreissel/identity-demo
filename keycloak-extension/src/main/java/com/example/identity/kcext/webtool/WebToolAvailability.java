package com.example.identity.kcext.webtool;

import org.keycloak.models.KeycloakSession;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The Web channel's declaration of the tools it can render, each in the one version its renderer
 * speaks ({@code <toolId>@<version>}, ADR-51), sent with every {@code upsertChannel} call; the
 * counterpart to the App channel's {@code availableTools}. Read from Keycloak's provider registry,
 * so no list is hardcoded.
 */
public final class WebToolAvailability {

    private WebToolAvailability() {
    }

    public static List<String> renderableTools(KeycloakSession session) {
        return session.getKeycloakSessionFactory()
                .getProviderFactoriesStream(WebToolRenderer.class)
                .map(f -> f.getId() + "@" + ((WebToolRendererFactory) f).version())
                .collect(Collectors.toList());
    }

    /** The version of {@code toolId} this extension speaks; only a tool it renders is ever called. */
    public static int versionOf(KeycloakSession session, String toolId) {
        WebToolRendererFactory factory = (WebToolRendererFactory) session.getKeycloakSessionFactory()
                .getProviderFactory(WebToolRenderer.class, toolId);
        if (factory == null) throw new IllegalStateException("No renderer for tool " + toolId);
        return factory.version();
    }
}
