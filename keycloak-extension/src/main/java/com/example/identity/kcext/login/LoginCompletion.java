package com.example.identity.kcext.login;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.federation.KcSubject;

import java.util.Objects;

/**
 * Whether an orchestrator answer ends this login step, and whether Keycloak may take it as done.
 * The orchestrator decides, but this step checks what it can check itself before it certifies the
 * level of its subflow: the account or invitation it already knows, and the level it was configured for.
 * An answer without a next step on a channel that is not logged in is an error, never a success.
 */
final class LoginCompletion {

    private LoginCompletion() {
    }

    sealed interface Verdict permits Continue, Complete, Refuse {
    }

    /** Not done yet: dispatch the next step. */
    record Continue() implements Verdict {
    }

    record Complete() implements Verdict {
    }

    /** Done by the orchestrator's word, but not by what this step knows. */
    record Refuse(String reason) implements Verdict {
    }

    /**
     * @param knownSubject the account or invitation this flow already carries (step-up), or {@code null}
     * @param certifiedAcr the level this execution's subflow stands for, or {@code null}
     */
    static Verdict judge(OrchestratorClient.ChannelResponse response, KcSubject knownSubject, String certifiedAcr) {
        OrchestratorClient.Next next = response.next();
        boolean done = (next != null && next.isAuthenticated()) || "AUTHENTICATED".equals(response.channelState());
        if (!done) {
            return next == null ? new Refuse("no next step, channel " + response.channelState()) : new Continue();
        }
        if (response.authDataSubject() == null) {
            return new Refuse("logged in without a subject");
        }
        // An invitation never raises an account's session, nor an account an invitation's.
        if (knownSubject != null && !Objects.equals(knownSubject, response.authDataSubject())) {
            return new Refuse(response.authDataSubject() + " instead of " + knownSubject);
        }
        // A misconfigured target would compare -1 with -1 and let every answer pass.
        if (certifiedAcr != null && OrchestratorNotes.acrRank(certifiedAcr) < 0) {
            return new Refuse("unknown target level " + certifiedAcr);
        }
        if (certifiedAcr != null && OrchestratorNotes.acrRank(response.authDataAcr()) < OrchestratorNotes.acrRank(certifiedAcr)) {
            return new Refuse("level " + response.authDataAcr() + " below " + certifiedAcr);
        }
        return new Complete();
    }
}
