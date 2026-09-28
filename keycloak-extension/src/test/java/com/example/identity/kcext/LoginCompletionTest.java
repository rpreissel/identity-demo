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
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("STARTED", null, null, null), null, null));
    }

    @Test
    void anAnswerWithoutNextOnALoggedInChannelIsDone() {
        assertInstanceOf(LoginCompletion.Complete.class, LoginCompletion.judge(response("AUTHENTICATED", null, 7L, "loa1"), null, "loa1"));
    }

    @Test
    void aFurtherStepContinues() {
        assertInstanceOf(LoginCompletion.Continue.class, LoginCompletion.judge(response("STARTED", SELECT, null, null), null, "loa2"));
    }

    @Test
    void anotherAccountThanTheOneTheFlowCarriesIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, 8L, "loa2"), 7L, "loa2"));
    }

    @Test
    void aLevelBelowWhatThisSubflowCertifiesIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, 7L, "loa1"), 7L, "loa2"));
    }

    @Test
    void aMissingLevelNeverSatisfiesAFloor() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, 7L, null), 7L, "loa1"));
    }

    @Test
    void doneWithoutAnAccountIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, null, "loa2"), null, null));
    }

    @Test
    void theSameAccountAtTheCertifiedLevelIsDone() {
        assertInstanceOf(LoginCompletion.Complete.class, LoginCompletion.judge(response("AUTHENTICATED", AUTHENTICATED, 7L, "loa2"), 7L, "loa2"));
    }

    private static OrchestratorClient.ChannelResponse response(String state, OrchestratorClient.Next next, Long accountId, String acr) {
        return new OrchestratorClient.ChannelResponse("channel-1", state, next, Map.of(), Map.of(), accountId, acr, Map.of());
    }
}
