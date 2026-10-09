package com.example.identity.kcext.federation;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ModelException;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.storage.ReadOnlyException;

import java.util.Map;

import static com.example.identity.kcext.KcTestFixtures.component;
import static com.example.identity.kcext.KcTestFixtures.unreachableOrchestrator;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Ein nicht erreichbarer Orchestrator ist ein Fehler, keine Antwort: weder „kein Nutzer“ noch
 * „falsches Passwort“, das Keycloaks Brute-Force-Schutz dem Nutzer anrechnete.
 */
class OrchestratorStorageProviderTest {

    private final ComponentModel component = component("orch-accounts");
    private final OrchestratorStorageProvider provider = new OrchestratorStorageProvider(null, component, () -> unreachableOrchestrator());
    private final OrchestratorUser user = new OrchestratorUser(null, null, component,
            new KcAccount(42, "max@example.com", "max@example.com", true, "Max", "Muster",
                    Map.of("orchestratorAccountId", "42")));

    @Test
    void anUnreachableOrchestratorIsAnErrorNotAWrongPassword() {
        assertThrows(ModelException.class, () -> provider.isValid(null, user, UserCredentialModel.password("geheim")));
    }

    @Test
    void anUnreachableOrchestratorIsAnErrorNotAnUnknownUser() {
        assertThrows(ModelException.class, () -> provider.getUserByUsername(null, "max@example.com"));
    }

    /**
     * Returning false would let Keycloak store the password locally, next to the orchestrator's
     * (ADR-38, Nachtrag 2026-10-03).
     */
    @Test
    void aPasswordIsNeverChangedThroughKeycloak() {
        assertThrows(ReadOnlyException.class, () -> provider.updateCredential(null, user, UserCredentialModel.password("neu")));
    }

    @Test
    void anotherCredentialTypeIsNotValidWithoutAskingTheOrchestrator() {
        assertFalse(provider.isValid(null, user, new UserCredentialModel("", "otp", "123456")));
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
