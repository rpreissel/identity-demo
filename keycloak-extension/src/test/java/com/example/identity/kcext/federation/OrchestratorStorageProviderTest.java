package com.example.identity.kcext.federation;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.ModelException;
import org.keycloak.representations.idm.ComponentRepresentation;

import static com.example.identity.kcext.KcTestFixtures.component;
import static com.example.identity.kcext.KcTestFixtures.unreachableOrchestrator;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Ein nicht erreichbarer Orchestrator ist ein Fehler, keine Antwort, nicht „kein Nutzer“.
 */
class OrchestratorStorageProviderTest {

    private final ComponentModel component = component("orch-accounts");
    private final OrchestratorStorageProvider provider = new OrchestratorStorageProvider(null, component, () -> unreachableOrchestrator());

    @Test
    void anUnreachableOrchestratorIsAnErrorNotAnUnknownUser() {
        assertThrows(ModelException.class, () -> provider.getUserByUsername(null, "max@example.com"));
    }

    /**
     * Keycloak checks and stores no credentials (ADR-58): without these interfaces it can neither
     * validate a password through the federation nor keep one of its own next to the orchestrator's.
     */
    @Test
    void keycloakNeitherChecksNorStoresCredentialsThroughTheFederation() {
        assertFalse(provider instanceof CredentialInputValidator);
        assertFalse(provider instanceof CredentialInputUpdater);
    }

    /**
     * Keycloak instanziiert den Provider auch, wenn es das Realm loescht. Eine unlesbare Config (hier
     * der maskierte Schluessel aus einer kopierten Admin-API-Antwort) darf erst beim ersten Aufruf
     * scheitern, sonst laesst sich das Realm nicht mehr zuruecksetzen (DPoP-demo-egyu).
     */
    @Test
    void aBrokenConfigFailsOnFirstUseNotWhenKeycloakCreatesTheProvider() {
        ComponentModel broken = component("orchestrator");
        broken.getConfig().putSingle("orchestratorBaseUrl", "http://127.0.0.1:1");
        broken.getConfig().putSingle("publicOrchestratorBaseUrl", "http://127.0.0.1:1");
        broken.getConfig().putSingle("peerAuthIssuer", "keycloak");
        broken.getConfig().putSingle("peerAuthAudience", "orchestrator");
        broken.getConfig().putSingle("peerAuthSigningKeyJwk", ComponentRepresentation.SECRET_VALUE);

        OrchestratorStorageProvider created = assertDoesNotThrow(
                () -> new OrchestratorStorageProviderFactory().create(null, broken));

        assertDoesNotThrow(() -> created.preRemove(null));
        assertThrows(IllegalStateException.class, () -> created.getUserByUsername(null, "max@example.com"));
    }
}
