package com.example.identity.kcext.grant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Der Grant schreibt acr und amr nur in die Sitzung, wenn sie die Form haben, die der Orchestrator
 * schickt: ein Niveau des Realms und Methodennamen.
 */
class AccountTokenClaimsTest {

    // An identification in the App (ident-eid, ident-nect) reaches loa3.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "loa1 | password",
            "loa2 | password,sms,auth_qr-2",
            "loa3 | eid"
    })
    void theOrchestratorsLevelsAndMethodNamesAreAccepted(String acr, String amr) {
        assertNull(AccountTokenClaims.problem(acr, amr));
    }

    // An empty unquoted value is null, '' is the empty string.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            " | ''",
            " | "
    })
    void withoutEvidenceNeitherIsSent(String acr, String amr) {
        assertNull(AccountTokenClaims.problem(acr, amr));
    }

    @ParameterizedTest
    @ValueSource(strings = {"loa4", "gold"})
    void anUnknownLevelIsRejected(String acr) {
        assertEquals("Unknown acr", AccountTokenClaims.problem(acr, "password"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password", "sms,,password", "sms, password", "pwd\"}"})
    void aMethodNameOutsideTheAlphabetIsRejected(String amr) {
        assertEquals("Malformed amr", AccountTokenClaims.problem("loa1", amr));
    }
}
