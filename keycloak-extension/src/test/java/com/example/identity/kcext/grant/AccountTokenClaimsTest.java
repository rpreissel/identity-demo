package com.example.identity.kcext.grant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Der Grant schreibt acr und amr nur in die Sitzung, wenn sie die Form haben, die der Orchestrator
 * schickt: ein Niveau des Realms und Methodennamen.
 */
class AccountTokenClaimsTest {

    @Test
    void theOrchestratorsLevelsAndMethodNamesAreAccepted() {
        assertNull(AccountTokenClaims.problem("loa1", "password"));
        assertNull(AccountTokenClaims.problem("loa2", "password,sms,auth_qr-2"));
        // An identification in the App (ident-eid, ident-nect) reaches loa3.
        assertNull(AccountTokenClaims.problem("loa3", "eid"));
    }

    @Test
    void withoutEvidenceNeitherIsSent() {
        assertNull(AccountTokenClaims.problem(null, ""));
        assertNull(AccountTokenClaims.problem(null, null));
    }

    @Test
    void anUnknownLevelIsRejected() {
        assertEquals("Unknown acr", AccountTokenClaims.problem("loa4", "password"));
        assertEquals("Unknown acr", AccountTokenClaims.problem("gold", "password"));
    }

    @Test
    void aMethodNameOutsideTheAlphabetIsRejected() {
        assertEquals("Malformed amr", AccountTokenClaims.problem("loa1", "Password"));
        assertEquals("Malformed amr", AccountTokenClaims.problem("loa1", "sms,,password"));
        assertEquals("Malformed amr", AccountTokenClaims.problem("loa1", "sms, password"));
        assertEquals("Malformed amr", AccountTokenClaims.problem("loa1", "pwd\"}"));
    }
}
