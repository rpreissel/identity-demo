package com.example.identity.kcext.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.events.EventType;

import com.example.identity.kcext.federation.KcSubject;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which Keycloak events reach the orchestrator's sign-in log: only the
 * logout of one of its own users. The after-commit, fire-and-forget delivery is the listener's own
 * structure ({@code enlistAfterCompletion}); this pins the filter in front of it.
 */
class SignInLogEventListenerTest {

    private static final String COMPONENT = "orch-accounts";
    private static final String OUR_USER = "f:" + COMPONENT + ":42";
    private static final String INVITATIONS = "3f0c2a1e-7b4d-4c55-9a0e-1d2b3c4d5e6f";

    @Test
    void aLogoutOfOneOfOurUsersIsReportedForItsAccount() {
        assertEquals(Optional.of(KcSubject.account(42)), SignInLogEventListener.subjectToReport(EventType.LOGOUT, OUR_USER, "kc-session", COMPONENT, INVITATIONS));
    }

    @ParameterizedTest
    @EnumSource(value = EventType.class, names = {"LOGIN", "LOGOUT_ERROR"})
    void anyOtherEventIsNot(EventType type) {
        assertEquals(Optional.empty(), SignInLogEventListener.subjectToReport(type, OUR_USER, "kc-session", COMPONENT, INVITATIONS));
    }

    // Keycloak's own admin is a local user without storage prefix; f:ldap belongs to some other federation.
    @ParameterizedTest
    @ValueSource(strings = {"8f14e45f-ceea-467a-a866-051f5a42c8b1", "f:ldap:42"})
    void aUserKeycloakKeepsItselfHasNoAccountToLogFor(String userId) {
        assertEquals(Optional.empty(), SignInLogEventListener.subjectToReport(EventType.LOGOUT, userId, "kc-session", COMPONENT, INVITATIONS));
    }

    // An empty value is null.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "f:" + COMPONENT + ":42           |",
            "                                 | kc-session",
            "f:" + COMPONENT + ":not-a-number | kc-session"
    })
    void withoutSessionOrWithAMalformedIdNothingIsReported(String userId, String session) {
        assertEquals(Optional.empty(), SignInLogEventListener.subjectToReport(EventType.LOGOUT, userId, session, COMPONENT, INVITATIONS));
    }

    @Test
    void aLogoutOfAnInvitationIsReportedForTheInvitation() {
        assertEquals(Optional.of(KcSubject.invitation("inv-hash")),
                SignInLogEventListener.subjectToReport(EventType.LOGOUT, "f:" + INVITATIONS + ":inv-hash", "kc-session", COMPONENT, INVITATIONS));
    }

    @Test
    void withoutAnInvitationFederationItsUsersAreNotReported() {
        assertEquals(Optional.empty(),
                SignInLogEventListener.subjectToReport(EventType.LOGOUT, "f:" + INVITATIONS + ":inv-hash", "kc-session", COMPONENT, null));
    }
}
