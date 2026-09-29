package com.example.identity.kcext.federation;

import com.example.identity.kcext.client.OrchestratorClient;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ModelException;
import org.keycloak.models.UserCredentialModel;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ein nicht erreichbarer Orchestrator ist ein Fehler, keine Antwort: weder „kein Nutzer“ noch
 * „falsches Passwort“, das Keycloaks Brute-Force-Schutz dem Nutzer anrechnete.
 */
class OrchestratorStorageProviderTest {

    // Port 1 nimmt keine Verbindung an: der Aufruf scheitert sofort mit einer IOException.
    private final OrchestratorClient unreachable = new OrchestratorClient(
            "http://127.0.0.1:1", "keycloak", "orchestrator", key());
    private final ComponentModel component = componentWithId("orch-accounts");
    private final OrchestratorStorageProvider provider = new OrchestratorStorageProvider(null, component, unreachable);
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

    @Test
    void anotherCredentialTypeIsNotValidWithoutAskingTheOrchestrator() {
        assertFalse(provider.isValid(null, user, new UserCredentialModel("", "otp", "123456")));
    }

    private static ECKey key() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("keycloak-1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ComponentModel componentWithId(String id) {
        ComponentModel model = new ComponentModel();
        model.setId(id);
        return model;
    }
}
