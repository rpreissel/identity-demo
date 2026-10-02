package com.example.identity.kcext.login;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.federation.KcSubject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Pure logic: when a login step may tell Keycloak it is done (review K-2). */
class LoginCompletionTest {

    private static final OrchestratorClient.Next AUTHENTICATED = new OrchestratorClient.Next("orchestrator", null, "authentication", "authenticated", null);
    private static final OrchestratorClient.Next SELECT = new OrchestratorClient.Next("orchestrator", null, "auth", "selectMethod", null);
    private static final KcSubject ACCOUNT_7 = KcSubject.account(7L);
    private static final KcSubject ACCOUNT_8 = KcSubject.account(8L);
    private static final KcSubject INVITATION = KcSubject.invitation("9f86d081");

    @Test
    void anAnswerWithoutNextOnAChannelNotLoggedInIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(answer("STARTED").build(), null, null));
    }

    @Test
    void anAnswerWithoutNextOnALoggedInChannelIsDone() {
        assertInstanceOf(LoginCompletion.Complete.class,
                LoginCompletion.judge(answer("AUTHENTICATED").subject(ACCOUNT_7).acr("loa1").build(), null, "loa1"));
    }

    @Test
    void aFurtherStepContinues() {
        assertInstanceOf(LoginCompletion.Continue.class, LoginCompletion.judge(answer("STARTED").next(SELECT).build(), null, "loa2"));
    }

    @Test
    void anotherAccountThanTheOneTheFlowCarriesIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class,
                LoginCompletion.judge(authenticated().subject(ACCOUNT_8).acr("loa2").build(), ACCOUNT_7, "loa2"));
    }

    @Test
    void aLevelBelowWhatThisSubflowCertifiesIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class,
                LoginCompletion.judge(authenticated().subject(ACCOUNT_7).acr("loa1").build(), ACCOUNT_7, "loa2"));
    }

    @Test
    void aMissingLevelNeverSatisfiesAFloor() {
        assertInstanceOf(LoginCompletion.Refuse.class,
                LoginCompletion.judge(authenticated().subject(ACCOUNT_7).build(), ACCOUNT_7, "loa1"));
    }

    @Test
    void doneWithoutASubjectIsRefused() {
        assertInstanceOf(LoginCompletion.Refuse.class, LoginCompletion.judge(authenticated().acr("loa2").build(), null, null));
    }

    @Test
    void theSameAccountAtTheCertifiedLevelIsDone() {
        assertInstanceOf(LoginCompletion.Complete.class,
                LoginCompletion.judge(authenticated().subject(ACCOUNT_7).acr("loa2").build(), ACCOUNT_7, "loa2"));
    }

    @Test
    void anInvitationNeverCompletesTheFlowOfAnAccount() {
        assertInstanceOf(LoginCompletion.Refuse.class,
                LoginCompletion.judge(authenticated().subject(INVITATION).acr("loa2").build(), ACCOUNT_7, "loa1"));
    }

    @Test
    void anAccountNeverRaisesTheSessionOfAnInvitation() {
        assertInstanceOf(LoginCompletion.Refuse.class,
                LoginCompletion.judge(authenticated().subject(ACCOUNT_7).acr("loa2").build(), INVITATION, "loa2"));
    }

    @Test
    void anInvitationAtTheCertifiedLevelIsDone() {
        assertInstanceOf(LoginCompletion.Complete.class,
                LoginCompletion.judge(authenticated().subject(INVITATION).acr("loa1").build(), null, "loa1"));
    }

    private static Answer answer(String state) {
        return new Answer(state);
    }

    /** An authenticated channel whose next step says so. */
    private static Answer authenticated() {
        return answer("AUTHENTICATED").next(AUTHENTICATED);
    }

    /** Builds the orchestrator's answer; what is not set stays empty. */
    private static final class Answer {
        private final String state;
        private OrchestratorClient.Next next;
        private KcSubject subject;
        private String acr;

        private Answer(String state) {
            this.state = state;
        }

        Answer next(OrchestratorClient.Next next) {
            this.next = next;
            return this;
        }

        Answer subject(KcSubject subject) {
            this.subject = subject;
            return this;
        }

        Answer acr(String acr) {
            this.acr = acr;
            return this;
        }

        OrchestratorClient.ChannelResponse build() {
            return new OrchestratorClient.ChannelResponse("channel-1", state, next, Map.of(), Map.of(), subject, acr, Map.of());
        }
    }
}
