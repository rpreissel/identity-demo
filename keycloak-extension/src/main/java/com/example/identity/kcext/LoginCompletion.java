package com.example.identity.kcext;

import java.util.Objects;

/**
 * Whether an orchestrator answer ends this login step, and whether Keycloak may take it as done.
 * The orchestrator decides, but this step checks what it can check itself before it certifies the
 * level of its subflow: the account it already knows, and the level it was configured for.
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
     * @param knownAccountId the account this flow already carries (step-up), or {@code null}
     * @param certifiedAcr   the level this execution's subflow stands for, or {@code null}
     */
    static Verdict judge(OrchestratorClient.ChannelResponse response, Long knownAccountId, String certifiedAcr) {
        OrchestratorClient.Next next = response.next();
        boolean done = (next != null && next.isAuthenticated()) || "AUTHENTICATED".equals(response.channelState());
        if (!done) {
            return next == null ? new Refuse("no next step, channel " + response.channelState()) : new Continue();
        }
        if (response.authDataAccountId() == null) {
            return new Refuse("logged in without an account");
        }
        if (knownAccountId != null && !Objects.equals(knownAccountId, response.authDataAccountId())) {
            return new Refuse("account " + response.authDataAccountId() + " instead of " + knownAccountId);
        }
        if (certifiedAcr != null && OrchestratorNotes.acrRank(response.authDataAcr()) < OrchestratorNotes.acrRank(certifiedAcr)) {
            return new Refuse("level " + response.authDataAcr() + " below " + certifiedAcr);
        }
        return new Complete();
    }
}
