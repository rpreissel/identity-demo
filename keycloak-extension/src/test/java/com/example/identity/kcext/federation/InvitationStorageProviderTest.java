package com.example.identity.kcext.federation;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ModelException;

import static com.example.identity.kcext.KcTestFixtures.component;
import static com.example.identity.kcext.KcTestFixtures.unreachableOrchestrator;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An invitation is found by its id only (ADR-48): never by name or address, so Keycloak's own
 * forms can never sign one in. An unreachable orchestrator is an error, never "unknown user".
 */
class InvitationStorageProviderTest {

    private static final String COMPONENT_ID = "05a332f1-f79d-46f6-9954-a3c95432e5c4";

    private final InvitationStorageProvider provider =
            new InvitationStorageProvider(null, component(COMPONENT_ID), unreachableOrchestrator());

    @Test
    void anUnreachableOrchestratorIsAnErrorNotAnUnknownUser() {
        assertThrows(ModelException.class, () -> provider.getUserById(null, "f:" + COMPONENT_ID + ":" + "a".repeat(64)));
    }

    @Test
    void anInvitationIsNeverFoundByNameOrAddress() {
        assertNull(provider.getUserByUsername(null, "invitation-" + "a".repeat(64)));
        assertNull(provider.getUserByEmail(null, "max@example.com"));
    }
}
