package com.example.identity.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.util.AcrStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All auth- and user-session note keys of this plugin and the small JSON bookkeeping around them, so
 * the authenticators agree on the same shapes.
 */
public final class OrchestratorNotes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** This flow run's channelSessionId, derived once and reused by every step of the same run. */
    static final String CHANNEL_SESSION_ID = "orchestrator_channel_session_id";
    /** The entry intent CHANNEL_SESSION_ID was opened for ("" = the default login/step-up) - see {@link #channelSessionIdFor}. */
    static final String CHANNEL_INTENT = "orchestrator_channel_intent";
    /** Which pending step this authenticator is currently showing - "select" or "tool". */
    static final String PENDING_KIND = "orchestrator_pending_kind";
    static final String PENDING_TOOL_ID = "orchestrator_pending_tool_id";
    static final String PENDING_TOOL_SESSION_ID = "orchestrator_pending_tool_session_id";
    /** JSON array of {nativeToolId, amrSourceId} - the full, current set (docs/05-api.md Abschnitt 3: no delta). */
    static final String NATIVE_AMR = "orchestrator_native_amr";
    /** Set once restoreData was already submitted this flow run, so a later resume doesn't resend it. */
    static final String RESTORE_SUBMITTED = "orchestrator_restore_submitted";

    /**
     * Copied into the UserSessionModel at session creation. Public because the App channel's custom
     * grant ({@link com.example.identity.kcext.grant.AccountTokenGrantType}) writes them too;
     * {@link OrchestratorAcrAmrMapper} reads them for either origin.
     */
    public static final String USER_SESSION_NOTE_ACR = "orchestrator_acr";
    public static final String USER_SESSION_NOTE_AMR = "orchestrator_amr";
    /** Written by the end-of-flow restore-data hook, see {@link #stashRestoreDataAtFlowEnd}. */
    static final String USER_SESSION_NOTE_RESTORE_DATA = "orchestrator_restore_data";

    /** The orchestrator accountId, once known - a durable Keycloak user attribute, read back on every later step-up. */
    static final String USER_ATTR_ACCOUNT_ID = AccountUsers.ACCOUNT_ID_ATTRIBUTE;

    private OrchestratorNotes() {
    }

    /**
     * The same value for the whole flow run (docs/05-api.md Abschnitt 3: "immer insert, nie find").
     * Keyed on the tab id, not the parent session id: Keycloak reuses the root authentication session
     * across requests of an SSO'd browser, so a step-up right after a login would collide with that
     * login's kc anchor (BINDING_MISMATCH). The tab id is fresh per authorization request.
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

    static List<OrchestratorClient.AmrEntry> nativeAmr(AuthenticationFlowContext context) {
        String raw = context.getAuthenticationSession().getAuthNote(NATIVE_AMR);
        List<OrchestratorClient.AmrEntry> entries = new ArrayList<>();
        if (raw == null || raw.isBlank()) return entries;
        try {
            for (JsonNode node : MAPPER.readTree(raw)) {
                entries.add(new OrchestratorClient.AmrEntry(node.get("nativeToolId").asText(), node.get("amrSourceId").asText()));
            }
        } catch (Exception ignored) {
            // Corrupt note; only appendNativeAmr writes it. Treat as empty.
        }
        return entries;
    }

    /** Appends or replaces (by nativeToolId) one native proof - the full, current set is always resent, never a delta. */
    static void appendNativeAmr(AuthenticationFlowContext context, String nativeToolId, String amrSourceId) {
        List<OrchestratorClient.AmrEntry> entries = new ArrayList<>(nativeAmr(context));
        entries.removeIf(e -> e.nativeToolId().equals(nativeToolId));
        entries.add(new OrchestratorClient.AmrEntry(nativeToolId, amrSourceId));
        ArrayNode array = MAPPER.createArrayNode();
        for (OrchestratorClient.AmrEntry entry : entries) {
            array.addObject().put("nativeToolId", entry.nativeToolId()).put("amrSourceId", entry.amrSourceId());
        }
        context.getAuthenticationSession().setAuthNote(NATIVE_AMR, array.toString());
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
     * The end-of-flow RestoreData hook (docs/05-api.md Abschnitt 3), called from
     * {@link OrchestratorResumeAuthenticator#onTopFlowSuccess} once the whole top-level flow is done.
     * On a first login no UserSessionModel exists yet; Keycloak copies user-session notes onto it
     * when it is created, so writing the note here is enough.
     */
    static void stashRestoreDataAtFlowEnd(KeycloakSession session, AuthenticationSessionModel authSession, OrchestratorClient client, Logger log) {
        String channelSessionId = authSession.getAuthNote(CHANNEL_SESSION_ID);
        if (channelSessionId == null) return; // Not a Keycloak-channel flow run.
        try {
            RealmModel realm = authSession.getParentSession().getRealm();
            UserSessionModel existing = resolveExistingUserSession(session, realm);
            String durableSessionId = existing != null ? existing.getId() : authSession.getParentSession().getId();
            long sessionExpiresAt = SessionEnd.epochSecond(realm, existing, Time.currentTime());
            String restoreData = client.restoreData(channelSessionId, durableSessionId, sessionExpiresAt);
            if (restoreData != null) {
                authSession.setUserSessionNote(USER_SESSION_NOTE_RESTORE_DATA, restoreData);
            }
        } catch (Exception e) {
            // Best-effort: a missed RestoreData write only means a later step-up starts without a
            // running start (docs/05-api.md Abschnitt 3), never a broken login.
            log.warnf(e, "Failed to fetch/stash RestoreData for channel %s", channelSessionId);
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

    static Long accountId(UserModel user) {
        String value = user == null ? null : user.getFirstAttribute(USER_ATTR_ACCOUNT_ID);
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    // Numeric Condition-LoA level to orchestrator ACR, like the per-execution "targetAcr" configs in
    // V1__realm.kc.kts. Needed here because it must be readable before the owning subflow runs.
    private static final Map<Integer, String> LOA_TO_ACR = Map.of(1, "loa1", 2, "loa2");

    /** The level's rank, {@code -1} for an unknown or missing one, so it never satisfies a floor. */
    static int acrRank(String acr) {
        return LOA_TO_ACR.entrySet().stream().filter(e -> e.getValue().equals(acr)).mapToInt(Map.Entry::getKey).findFirst().orElse(-1);
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
