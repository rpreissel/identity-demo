package com.example.identity.kcext.federation;

import com.example.identity.kcext.client.OrchestratorClient;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ModelException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An invitation is found by its id only (ADR-48): never by name or address, so Keycloak's own
 * forms can never sign one in. An unreachable orchestrator is an error, never "unknown user".
 */
class InvitationStorageProviderTest {

    private static final String COMPONENT_ID = "05a332f1-f79d-46f6-9954-a3c95432e5c4";

    // Port 1 accepts no connection: the call fails at once with an IOException.
    private final OrchestratorClient unreachable = new OrchestratorClient(
            "http://127.0.0.1:1", "keycloak", "orchestrator", key());
    private final InvitationStorageProvider provider = new InvitationStorageProvider(null, component(), unreachable);

    @Test
    void anUnreachableOrchestratorIsAnErrorNotAnUnknownUser() {
        assertThrows(ModelException.class, () -> provider.getUserById(null, "f:" + COMPONENT_ID + ":" + "a".repeat(64)));
    }

    @Test
    void anInvitationIsNeverFoundByNameOrAddress() {
        assertNull(provider.getUserByUsername(null, "invitation-" + "a".repeat(64)));
        assertNull(provider.getUserByEmail(null, "max@example.com"));
    }

    private static ECKey key() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("keycloak-1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ComponentModel component() {
        ComponentModel model = new ComponentModel();
        model.setId(COMPONENT_ID);
        return model;
    }
}
