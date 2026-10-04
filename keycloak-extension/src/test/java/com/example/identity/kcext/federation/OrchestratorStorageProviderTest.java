package com.example.identity.kcext.federation;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ModelException;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.storage.ReadOnlyException;

import java.util.Map;

import static com.example.identity.kcext.KcTestFixtures.component;
import static com.example.identity.kcext.KcTestFixtures.unreachableOrchestrator;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ein nicht erreichbarer Orchestrator ist ein Fehler, keine Antwort: weder „kein Nutzer“ noch
 * „falsches Passwort“, das Keycloaks Brute-Force-Schutz dem Nutzer anrechnete.
 */
class OrchestratorStorageProviderTest {

    private final ComponentModel component = component("orch-accounts");
    private final OrchestratorStorageProvider provider = new OrchestratorStorageProvider(null, component, unreachableOrchestrator());
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
}
