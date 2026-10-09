package com.example.identity.kcext.login;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.federation.AccountUsers;
import com.example.identity.kcext.token.OrchestratorAcrAmrMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.util.AcrStore;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All auth- and user-session note keys of this plugin and the small JSON bookkeeping around them, so
 * the authenticators agree on the same shapes.
 */
public final class OrchestratorNotes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** This flow run's channelSessionId, derived once and reused by every step of the same run. */
    public static final String CHANNEL_SESSION_ID = "orchestrator_channel_session_id";
    /** The entry intent CHANNEL_SESSION_ID was opened for ("" = the default login/step-up) - see {@link #channelSessionIdFor}. */
    static final String CHANNEL_INTENT = "orchestrator_channel_intent";
    /** Which pending step this authenticator is currently showing - "select" or "tool". */
    public static final String PENDING_KIND = "orchestrator_pending_kind";
    public static final String PENDING_TOOL_ID = "orchestrator_pending_tool_id";
    public static final String PENDING_TOOL_SESSION_ID = "orchestrator_pending_tool_session_id";

    /**
     * Copied into the UserSessionModel at session creation. Public because the App channel's custom
     * grant ({@link com.example.identity.kcext.grant.AccountTokenGrantType}) writes them too;
     * {@link OrchestratorAcrAmrMapper} reads them for either origin.
     */
    public static final String USER_SESSION_NOTE_ACR = "orchestrator_acr";
    public static final String USER_SESSION_NOTE_AMR = "orchestrator_amr";

    /** The orchestrator accountId, once known - a durable Keycloak user attribute, read back on every later step-up. */
    public static final String USER_ATTR_ACCOUNT_ID = AccountUsers.ACCOUNT_ID_ATTRIBUTE;

    private OrchestratorNotes() {
    }

    /**
     * The same value for the whole flow run (docs/05-api.md Abschnitt 3b: "immer insert, nie find").
     * Keyed on the tab id, not the parent session id: Keycloak reuses the root authentication session
     * across requests of an SSO'd browser, so a step-up right after a login would collide with that
     * login's kc binding (BINDING_MISMATCH). The tab id is fresh per authorization request.
     */
    static String channelSessionId(AuthenticationFlowContext context) {
        return channelSessionId(context.getAuthenticationSession());
    }

    /**
     * Variant for callers without an {@link AuthenticationFlowContext}, such as
     * {@link OrchestratorManageMethodsRequiredAction}.
     */
    static String channelSessionId(AuthenticationSessionModel authSession) {
        String existing = authSession.getAuthNote(CHANNEL_SESSION_ID);
        if (existing != null) return existing;
        String tabId = authSession.getTabId();
        String derived = UUID.nameUUIDFromBytes(("kc-auth-session-tab:" + tabId).getBytes()).toString();
        authSession.setAuthNote(CHANNEL_SESSION_ID, derived);
        return derived;
    }

    /**
     * The channel for this flow run's entry intent. The "Registrieren" link moves the same tab from
     * login to registration, but the orchestrator honours an entry intent only on a channel's first
     * call. A different intent therefore gets its own channel (tab id plus intent), which replaces
     * the current one; the abandoned login channel runs out.
     */
    static String channelSessionIdFor(AuthenticationSessionModel authSession, String intent) {
        String wanted = intent == null ? "" : intent.toLowerCase();
        String current = authSession.getAuthNote(CHANNEL_SESSION_ID);
        String currentIntent = authSession.getAuthNote(CHANNEL_INTENT);
        if (current == null || wanted.equals(currentIntent == null ? "" : currentIntent)) {
            String id = channelSessionId(authSession);
            authSession.setAuthNote(CHANNEL_INTENT, wanted);
            return id;
        }
        String derived = UUID.nameUUIDFromBytes(("kc-auth-session-tab:" + authSession.getTabId() + ":" + wanted).getBytes()).toString();
        authSession.setAuthNote(CHANNEL_SESSION_ID, derived);
        authSession.setAuthNote(CHANNEL_INTENT, wanted);
        authSession.removeAuthNote(PENDING_KIND);
        authSession.removeAuthNote(PENDING_TOOL_ID);
        authSession.removeAuthNote(PENDING_TOOL_SESSION_ID);
        return derived;
    }

    /**
     * The SSO session this browser already holds a valid identity cookie for, if any. Mid-flow,
     * {@code getContext().getUserSession()} is still null: Keycloak sets it only at the end of a
     * successful flow. So this verifies the identity cookie directly, like {@code auth-cookie} does.
     */
    static UserSessionModel resolveExistingUserSession(KeycloakSession session, RealmModel realm) {
        AuthenticationManager.AuthResult authResult =
                AuthenticationManager.authenticateIdentityCookie(session, realm, true);
        return authResult == null ? null : authResult.session();
    }

    /**
     * The end-of-flow hook, called from {@link OrchestratorResumeAuthenticator#onTopFlowSuccess} once
     * the whole top-level flow is done: tells the orchestrator which Keycloak session this channel
     * belongs to, so it records what the channel proved for that session (ADR-59). On a first login
     * no UserSessionModel exists yet; Keycloak creates it with the parent session's id.
     */
    static void reportFlowEnd(KeycloakSession session, AuthenticationSessionModel authSession, OrchestratorClient client, Logger log) {
        String channelSessionId = authSession.getAuthNote(CHANNEL_SESSION_ID);
        if (channelSessionId == null) return; // Not a Keycloak-channel flow run.
        try {
            RealmModel realm = authSession.getParentSession().getRealm();
            UserSessionModel existing = resolveExistingUserSession(session, realm);
            String durableSessionId = existing != null ? existing.getId() : authSession.getParentSession().getId();
            long sessionExpiresAt = SessionEnd.epochSecond(realm, existing, Time.currentTime());
            client.flowEnded(channelSessionId, durableSessionId, sessionExpiresAt);
        } catch (Exception e) {
            // Best-effort: a missed report only means a later step-up starts without the session's
            // earlier proofs (docs/05-api.md Abschnitt 3b), never a broken login.
            log.warnf(e, "Failed to report the end of the flow run for channel %s", channelSessionId);
        }
    }

    /**
     * Copies a response's acr/amr onto this flow run's user-session notes. Every caller that
     * dispatches a {@code ChannelResponse} must do this, or a completed step-up never raises the acr
     * of the next token.
     */
    static void applyAuthData(AuthenticationSessionModel authSession, OrchestratorClient.ChannelResponse response) {
        if (response.authDataAcr() != null) {
            authSession.setUserSessionNote(USER_SESSION_NOTE_ACR, response.authDataAcr());
        }
        if (!response.authDataAmr().isEmpty()) {
            authSession.setUserSessionNote(USER_SESSION_NOTE_AMR, String.join(",", response.authDataAmr().keySet()));
        }
    }

    public static Long accountId(UserModel user) {
        String value = user == null ? null : user.getFirstAttribute(USER_ATTR_ACCOUNT_ID);
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    // Numeric Condition-LoA level to orchestrator ACR, like the per-execution "targetAcr" configs in
    // V1__realm.kc.kts. Needed here because it must be readable before the owning subflow runs.
    private static final Map<Integer, String> LOA_TO_ACR = Map.of(1, "loa1", 2, "loa2");

    private static final Map<String, Integer> ACR_RANK = Map.of("loa1", 1, "loa2", 2, "loa3", 3);

    /** The level's rank, {@code -1} for an unknown or missing one, so it never satisfies a floor. */
    static int acrRank(String acr) {
        return acr == null ? -1 : ACR_RANK.getOrDefault(acr, -1);
    }

    // Every level the orchestrator can certify. More than the browser flow can ask for
    // (LOA_TO_ACR): loa3 comes only from an identification in the App (ident-eid, ident-nect),
    // never from a Condition-LoA subflow here.
    private static final Set<String> ORCHESTRATOR_ACRS = Set.of("loa1", "loa2", "loa3");

    /** Ob {@code acr} eines der Niveaus ist, die der Orchestrator bescheinigen kann. */
    public static boolean isKnownAcr(String acr) {
        return ORCHESTRATOR_ACRS.contains(acr);
    }

    /**
     * Keycloak's requested level for the whole top-level flow (e.g. {@code acr_values=2}) as
     * orchestrator ACR, independent of the running Condition-LoA subflow. Channels must use this as
     * floor: with only LoA-1's "loa1", the entry journey finishes at the first loa1 proof and LoA-2
     * has no journey left to raise, so the step-up never happens. Null without a requested level of
     * ours; callers then fall back to their static config.
     */
    static String requestedAcr(AuthenticationFlowContext context) {
        AcrStore acrStore = new AcrStore(context.getSession(), context.getAuthenticationSession());
        int requested = acrStore.getRequestedLevelOfAuthentication(context.getTopLevelFlow());
        return LOA_TO_ACR.get(requested);
    }
}
