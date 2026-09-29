package com.example.identity.kcext;

import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.utils.StripSecretsUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.idm.ComponentRepresentation;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Der Peer-Auth-Signaturschluessel entsteht nur in validateConfiguration, also wenn Keycloak die
 * Komponente anlegt oder aendert und das Modell selbst speichert. Das Lesen schreibt nie, sonst
 * scheitern parallele User-Anlagen an Keycloaks optimistischer Sperre.
 */
class OrchestratorSettingsTest {

    @Test
    void validateConfigurationAddsTheSigningKeyToTheModelKeycloakPersists() {
        ComponentModel model = component();

        new OrchestratorStorageProviderFactory().validateConfiguration(null, null, model);

        assertNotNull(OrchestratorSettings.from(model).peerAuthSigningKey());
    }

    @Test
    void validateConfigurationKeepsAnExistingKey() {
        ComponentModel model = component();
        var factory = new OrchestratorStorageProviderFactory();
        factory.validateConfiguration(null, null, model);
        String first = model.getConfig().getFirst("peerAuthSigningKeyJwk");

        // Jedes Speichern in der Admin-Console laeuft hier durch - ein neuer Schluessel wuerde jede
        // gerade signierte Assertion entwerten.
        factory.validateConfiguration(null, null, model);

        assertEquals(first, model.getConfig().getFirst("peerAuthSigningKeyJwk"));
    }

    @Test
    void validateConfigurationReplacesTheMaskCopiedFromTheAdminApi() {
        ComponentModel model = component();
        // So kommt eine Komponente an, deren Config aus der Admin-API kopiert wurde (V2__user_federation).
        model.getConfig().putSingle("peerAuthSigningKeyJwk", ComponentRepresentation.SECRET_VALUE);

        new OrchestratorStorageProviderFactory().validateConfiguration(null, null, model);

        assertNotNull(OrchestratorSettings.from(model).peerAuthSigningKey());
    }

    @Test
    void readingAComponentWithoutKeyFailsInsteadOfWriting() {
        ComponentModel model = component();

        assertThrows(IllegalStateException.class, () -> OrchestratorSettings.from(model));
    }

    @Test
    void theAdminApiAndTheRealmExportShowTheSigningKeyOnlyMasked() throws Exception {
        ComponentModel model = component();
        new OrchestratorStorageProviderFactory().validateConfiguration(null, null, model);
        ComponentRepresentation representation = new ComponentRepresentation();
        representation.setConfig(new MultivaluedHashMap<>(model.getConfig()));
        Map<String, ProviderConfigProperty> declared = OrchestratorSettings.CONFIG_PROPERTIES.stream()
                .collect(Collectors.toMap(ProviderConfigProperty::getName, Function.identity()));

        // Keycloak's own masking, as the admin API and the export apply it (protected in Keycloak).
        Method strip = StripSecretsUtils.class.getDeclaredMethod("stripComponent", Map.class, ComponentRepresentation.class);
        strip.setAccessible(true);
        ComponentRepresentation stripped = (ComponentRepresentation) strip.invoke(null, declared, representation);

        assertEquals(ComponentRepresentation.SECRET_VALUE, stripped.getConfig().getFirst(OrchestratorSettings.PEER_AUTH_SIGNING_KEY));
        assertEquals("http://orchestrator:8080", stripped.getConfig().getFirst(OrchestratorSettings.ORCHESTRATOR_BASE_URL));
    }

    private static ComponentModel component() {
        ComponentModel model = new ComponentModel();
        model.setName("orchestrator");
        model.getConfig().putSingle(OrchestratorSettings.ORCHESTRATOR_BASE_URL, "http://orchestrator:8080");
        model.getConfig().putSingle(OrchestratorSettings.PUBLIC_ORCHESTRATOR_BASE_URL, "http://localhost:8080");
        model.getConfig().putSingle(OrchestratorSettings.PEER_AUTH_ISSUER, "identity-demo-keycloak");
        model.getConfig().putSingle(OrchestratorSettings.PEER_AUTH_AUDIENCE, "identity-demo-orchestrator");
        return model;
    }
}
