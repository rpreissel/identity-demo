package com.example.identity.kcext.model;

import org.keycloak.models.UserModel;

import java.util.Set;

/**
 * The note and attribute names every package of this plugin reads, and the two checks on them. A
 * package of its own, so the login flow, the federation, the token mapper and the resources agree
 * on them without depending on each other.
 */
public final class SessionNotes {

    /** This flow run's channelSessionId, derived once and reused by every step of the same run. */
    public static final String CHANNEL_SESSION_ID = "orchestrator_channel_session_id";
    /** Which page the run currently shows: "select", "tool", "confirm" or, in the required action, "list". */
    public static final String PENDING_KIND = "orchestrator_pending_kind";
    public static final String PENDING_TOOL_ID = "orchestrator_pending_tool_id";
    public static final String PENDING_TOOL_SESSION_ID = "orchestrator_pending_tool_session_id";

    /**
     * Copied into the UserSessionModel at session creation, by the login flow and by the App
     * channel's grant alike; the token mapper reads them for either origin.
     */
    public static final String USER_SESSION_NOTE_ACR = "orchestrator_acr";
    public static final String USER_SESSION_NOTE_AMR = "orchestrator_amr";

    /** The orchestrator accountId on a federated user - a durable attribute, read on every later step-up. */
    public static final String ACCOUNT_ID_ATTRIBUTE = "orchestratorAccountId";

    // Every level the orchestrator can certify; loa3 comes only from an identification in the App.
    private static final Set<String> ORCHESTRATOR_ACRS = Set.of("loa1", "loa2", "loa3");

    private SessionNotes() {
    }

    /** The orchestrator accountId of [user], or null for none of its accounts. */
    public static Long accountId(UserModel user) {
        String value = user == null ? null : user.getFirstAttribute(ACCOUNT_ID_ATTRIBUTE);
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    /** Whether {@code acr} is one of the levels the orchestrator can certify. */
    public static boolean isKnownAcr(String acr) {
        return ORCHESTRATOR_ACRS.contains(acr);
    }
}
