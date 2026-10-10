package com.example.identity.kcext.federation;

import com.example.identity.kcext.model.KcAccount;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.storage.ReadOnlyException;

import java.util.Map;

import static com.example.identity.kcext.KcTestFixtures.component;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A federated user is the orchestrator account. Its id, the token's sub, comes
 * from the account id, never from the address; what the account owns is read-only in Keycloak.
 */
class OrchestratorUserTest {

    private final ComponentModel component = component("orch-accounts");
    private final KcAccount account = new KcAccount(42, "max@example.com", "max@example.com", true, "Max", "Muster",
            Map.of("orchestratorAccountId", "42", "person_id", "P000000001"));
    private final OrchestratorUser user = new OrchestratorUser(null, null, component, account);

    @Test
    void theIdIsTheAccountIdNotTheAddress() {
        assertEquals("f:orch-accounts:42", user.getId());
    }

    @Test
    void namesAddressAndAttributesComeFromTheAccount() {
        assertEquals("max@example.com", user.getUsername());
        assertEquals("max@example.com", user.getEmail());
        assertEquals("Max", user.getFirstName());
        assertEquals("Muster", user.getLastName());
        assertEquals("42", user.getFirstAttribute("orchestratorAccountId"));
        assertEquals("P000000001", user.getFirstAttribute("person_id"));
        assertTrue(user.isEmailVerified());
    }

    @Test
    void whatTheAccountOwnsCannotBeChangedThroughKeycloak() {
        assertThrows(ReadOnlyException.class, () -> user.setEmail("other@example.com"));
        assertThrows(ReadOnlyException.class, () -> user.setUsername("other"));
        assertThrows(ReadOnlyException.class, () -> user.setFirstName("Moritz"));
        assertThrows(ReadOnlyException.class, () -> user.setSingleAttribute("orchestratorAccountId", "43"));
        assertThrows(ReadOnlyException.class, () -> user.setEmailVerified(false));
    }

    @Test
    void anAccountWithoutAddressHasNoEmail() {
        OrchestratorUser noMail = new OrchestratorUser(null, null, component,
                new KcAccount(7, "account-7", null, false, "Unbekannt", "(nicht identifiziert)", Map.of()));
        assertNull(noMail.getEmail());
        assertEquals("account-7", noMail.getUsername());
    }
}
