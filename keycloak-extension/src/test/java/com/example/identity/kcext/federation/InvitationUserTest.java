package com.example.identity.kcext.federation;

import com.example.identity.kcext.model.KcSubject;

import com.example.identity.kcext.model.KcInvitation;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.storage.ReadOnlyException;
import java.util.Map;

import static com.example.identity.kcext.KcTestFixtures.component;
import static org.junit.jupiter.api.Assertions.assertAll;
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

    private final ComponentModel component = component("orch-invitations");

    private InvitationUser user(boolean open) {
        return new InvitationUser(null, null, component, new KcInvitation("9f86d081", "invitation-9f86d081", open, "Max", "Muster",
                Map.of("orchestratorInvitation", "9f86d081", "orchestratorProcess", "beitragsrueckerstattung", "personId", "P000000001")));
    }

    @Test
    void theIdLiesInTheInvitationFederationNeverInTheAccounts() {
        assertEquals("f:orch-invitations:9f86d081", user(true).getId());
    }

    @Test
    void itCarriesThePersonAndBothMarkers() {
        InvitationUser user = user(true);
        assertEquals("Max", user.getFirstName());
        assertEquals("P000000001", user.getFirstAttribute("personId"));
        assertEquals("9f86d081", user.getFirstAttribute("orchestratorInvitation"));
        assertEquals("beitragsrueckerstattung", user.getFirstAttribute("orchestratorProcess"));
    }

    @Test
    void itHasNeitherAddressNorAccountId() {
        InvitationUser user = user(true);
        assertNull(user.getEmail());
        assertNull(user.getFirstAttribute("orchestratorAccountId"));
    }

    @Test
    void itsSubjectIsTheInvitation() {
        assertEquals(KcSubject.invitation("9f86d081"), Subjects.of(user(true)));
    }

    @Test
    void anEndedInvitationIsADisabledUser() {
        assertTrue(user(true).isEnabled());
        assertFalse(user(false).isEnabled());
    }

    @Test
    void nothingOfItCanBeChangedThroughKeycloak() {
        InvitationUser user = user(true);
        assertAll(
                () -> assertThrows(ReadOnlyException.class, () -> user.setEnabled(true)),
                () -> assertThrows(ReadOnlyException.class, () -> user.setSingleAttribute("orchestratorProcess", "anything")),
                () -> assertThrows(ReadOnlyException.class, () -> user.setUsername("other")),
                // An account id entered in Keycloak would make it look like that account.
                () -> assertThrows(ReadOnlyException.class, () -> user.setSingleAttribute("orchestratorAccountId", "42")));
    }
}
