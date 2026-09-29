package com.example.identity.kcext;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Pure logic: when a login step may tell Keycloak it is done (review K-2). */
class LoginCompletionTest {

    private static final OrchestratorClient.Next AUTHENTICATED = new OrchestratorClient.Next("orchestrator", null, "authentication", "authenticated", null);
    private static final OrchestratorClient.Next SELECT = new OrchestratorClient.Next("orchestrator", null, "auth", "selectMethod", null);

    @Test
    void anAnswerWithoutNextOnAChannelNotLoggedInIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("STARTED", null, (KcSubject) null, null), null, null));
    }

    @Test
    void anAnswerWithoutNextOnALoggedInChannelIsDone() {
        assertInstanceOf(LoginCompletion.Complete.class, LoginCompletion.judge(response("AUTHENTICATED", null, 7L, "loa1"), null, "loa1"));
    }

    @Test
    void aFurtherStepContinues() {
        assertInstanceOf(LoginCompletion.Continue.class, LoginCompletion.judge(response("STARTED", SELECT, (KcSubject) null, null), null, "loa2"));
    }

    @Test
    void anotherAccountThanTheOneTheFlowCarriesIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, 8L, "loa2"), ACCOUNT_7, "loa2"));
    }

    @Test
    void aLevelBelowWhatThisSubflowCertifiesIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, 7L, "loa1"), ACCOUNT_7, "loa2"));
    }

    @Test
    void aMissingLevelNeverSatisfiesAFloor() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, 7L, null), ACCOUNT_7, "loa1"));
    }

    @Test
    void doneWithoutASubjectIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, (KcSubject) null, "loa2"), null, null));
    }

    @Test
    void theSameAccountAtTheCertifiedLevelIsDone() {
        assertInstanceOf(LoginCompletion.Complete.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, 7L, "loa2"), ACCOUNT_7, "loa2"));
    }

    @Test
    void anInvitationNeverCompletesTheFlowOfAnAccount() {
        assertInstanceOf(LoginCompletion.Refuse.class,
                LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, INVITATION, "loa2"), ACCOUNT_7, "loa1"));
    }

    @Test
    void anAccountNeverRaisesTheSessionOfAnInvitation() {
        assertInstanceOf(LoginCompletion.Refuse.class,
                LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, ACCOUNT_7, "loa2"), INVITATION, "loa2"));
    }

    @Test
    void anInvitationAtTheCertifiedLevelIsDone() {
        assertInstanceOf(LoginCompletion.Complete.class,
                LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, INVITATION, "loa1"), null, "loa1"));
    }

    private static final KcSubject ACCOUNT_7 = KcSubject.account(7L);
    private static final KcSubject INVITATION = KcSubject.invitation("9f86d081");

    private static OrchestratorClient.ChannelResponse response(String state, OrchestratorClient.Next next, Long accountId, String acr) {
        return response(state, next, accountId == null ? null : KcSubject.account(accountId), acr);
    }

    private static OrchestratorClient.ChannelResponse response(String state, OrchestratorClient.Next next, KcSubject subject, String acr) {
        return new OrchestratorClient.ChannelResponse("channel-1", state, next, Map.of(), Map.of(), subject, acr, Map.of());
    }
}
