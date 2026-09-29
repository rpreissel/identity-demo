package com.example.identity.kcext;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.storage.ReadOnlyException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invitation is a Keycloak user of its own (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md): its id
 * lies in the invitation federation, it carries the markers, and it is enabled only while open.
 */
class InvitationUserTest {

    private final ComponentModel component = componentWithId("orch-invitations");

    private InvitationUser user(boolean open) {
        return new InvitationUser(null, null, component, new KcInvitation("9f86d081", "invitation-9f86d081", open, "Max", "Muster",
                Map.of("orchestratorInvitation", "9f86d081", "orchestratorProcess", "beitragsrueckerstattung", "personId", "P000000001")));
    }

    @Test
    void theIdLiesInTheInvitationFederationNeverInTheAccounts() {
        assertEquals("f:orch-invitations:9f86d081", user(true).getId());
    }

    @Test
    void itCarriesThePersonAndBothMarkersButNoAddress() {
        InvitationUser user = user(true);
        assertEquals("Max", user.getFirstName());
        assertEquals("P000000001", user.getFirstAttribute("personId"));
        assertEquals("9f86d081", user.getFirstAttribute("orchestratorInvitation"));
        assertEquals("beitragsrueckerstattung", user.getFirstAttribute("orchestratorProcess"));
        assertNull(user.getEmail());
        assertNull(user.getFirstAttribute("orchestratorAccountId"));
        assertEquals(KcSubject.invitation("9f86d081"), KcSubject.of(user));
    }

    @Test
    void anEndedInvitationIsADisabledUser() {
        assertTrue(user(true).isEnabled());
        assertFalse(user(false).isEnabled());
    }

    @Test
    void nothingOfItCanBeChangedThroughKeycloak() {
        InvitationUser user = user(true);
        assertThrows(ReadOnlyException.class, () -> user.setEnabled(true));
        assertThrows(ReadOnlyException.class, () -> user.setSingleAttribute("orchestratorProcess", "anything"));
        assertThrows(ReadOnlyException.class, () -> user.setUsername("other"));
        // An account id entered in Keycloak would make it look like that account.
        assertThrows(ReadOnlyException.class, () -> user.setSingleAttribute("orchestratorAccountId", "42"));
    }

    private static ComponentModel componentWithId(String id) {
        ComponentModel model = new ComponentModel();
        model.setId(id);
        return model;
    }
}
