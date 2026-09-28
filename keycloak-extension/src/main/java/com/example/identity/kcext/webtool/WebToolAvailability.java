package com.example.identity.kcext.webtool;

import org.keycloak.models.KeycloakSession;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The Web channel's declaration of the toolIds it can render, sent with every {@code upsertChannel}
 * call; the counterpart to the App channel's {@code availableTools}. Read from Keycloak's provider
 * registry, so no list is hardcoded.
 */
public final class WebToolAvailability {

    private WebToolAvailability() {
    }

    public static List<String> renderableToolIds(KeycloakSession session) {
        return session.getKeycloakSessionFactory()
                .getProviderFactoriesStream(WebToolRenderer.class)
                .map(f -> f.getId())
                .collect(Collectors.toList());
    }
}
