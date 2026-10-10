package com.example.identity.kcext.federation;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.ModelException;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.storage.ReadOnlyException;

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

    /** Keycloak checks no credentials through the federation (ADR-58). */
    @Test
    void keycloakChecksNoCredentialsThroughTheFederation() {
        assertFalse(provider instanceof CredentialInputValidator);
    }

    /** An admin's "reset password" must not leave a Keycloak password next to the orchestrator's methods. */
    @Test
    void keycloakStoresNoCredentialOfItsOwn() {
        assertThrows(ReadOnlyException.class,
                () -> provider.updateCredential(null, null, UserCredentialModel.password("geheim")));
        assertThrows(ReadOnlyException.class,
                () -> provider.updateCredential(null, null, UserCredentialModel.otp("totp", "123456")));
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
