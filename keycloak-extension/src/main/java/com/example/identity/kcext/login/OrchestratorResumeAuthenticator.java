package com.example.identity.kcext.login;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.federation.KcSubject;
import com.example.identity.kcext.webtool.WebToolAvailability;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowCallback;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.List;

/**
 * Runs first in {@code orchestrator-browser}, inside its own small subflow so that
 * {@link AuthenticationFlowCallback} registers. If the browser holds a valid identity cookie, it opens
 * this flow run's channel and resubmits the RestoreData a prior run stashed on that session
 * (docs/05-api.md Abschnitt 3). The binding stays {@code channelSessionId}, never the SSO session id.
 *
 * <p>Without a session it does nothing: the channel's first call fixes the entry journey's candidate
 * list, and a later account binding does not recompute it. So the LoA authenticator, which knows the
 * account, makes that first call. Never renders a form and never fails the login (best effort).
 */
public class OrchestratorResumeAuthenticator implements AuthenticationFlowCallback {

    private static final Logger LOG = Logger.getLogger(OrchestratorResumeAuthenticator.class);

    private final KeycloakSession session;
    private final OrchestratorClient client;

    OrchestratorResumeAuthenticator(KeycloakSession session, OrchestratorClient client) {
        this.session = session;
        this.client = client;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            var existingUserSession = OrchestratorNotes.resolveExistingUserSession(context.getSession(), context.getRealm());
            // A process access has nothing to resume: its evidence never travels (ADR-48), and the
            // orchestrator does not raise it.
            if (existingUserSession != null && KcSubject.of(existingUserSession.getUser()) instanceof KcSubject s
                    && s.kind() == KcSubject.Kind.INVITATION) {
                context.success();
                return;
            }
            if (existingUserSession != null) {
                // A fresh channel for this flow run. The link to the existing session travels only
                // in restoreData, never in the binding (see OrchestratorNotes.channelSessionId).
                String newChannelSessionId = OrchestratorNotes.channelSessionId(context);

                // context.getUser() is not set yet this early; the UserSessionModel carries the user.
                KcSubject subject = KcSubject.of(existingUserSession.getUser());
                String restoreData = null;
                if (!"true".equals(authSession.getAuthNote(OrchestratorNotes.RESTORE_SUBMITTED))) {
                    restoreData = existingUserSession.getNote(OrchestratorNotes.USER_SESSION_NOTE_RESTORE_DATA);
                    authSession.setAuthNote(OrchestratorNotes.RESTORE_SUBMITTED, "true");
                }

                // Raise the floor to Keycloak's requested level, so a resumed proof cannot finish
                // the journey below it. No native amr yet: this runs before any native authenticator.
                OrchestratorClient.ChannelResponse response = client.upsertChannel(
                        newChannelSessionId, subject, OrchestratorNotes.requestedAcr(context), List.of(), restoreData, existingUserSession.getId(),
                        WebToolAvailability.renderableToolIds(context.getSession()), null
                );
                OrchestratorNotes.applyAuthData(authSession, response);
            }
        } catch (Exception e) {
            LOG.warnf(e, "OrchestratorResumeAuthenticator failed - continuing without a resumed channel");
        }
        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        context.success();
    }

    @Override
    public void onParentFlowSuccess(AuthenticationFlowContext context) {
        // Fires right after authenticate(), at the start of the flow: too early for RestoreData.
        // onTopFlowSuccess is the end-of-flow hook the wrapper subflow exists for.
    }

    @Override
    public void onTopFlowSuccess(AuthenticationFlowModel topFlow) {
        // Fires once at the end of the whole top-level flow (docs/05-api.md Abschnitt 3,
        // restore-data). Only the flow model is available, so the session comes from the context.
        AuthenticationSessionModel authSession = session.getContext().getAuthenticationSession();
        if (authSession == null) return;
        OrchestratorNotes.stashRestoreDataAtFlowEnd(session, authSession, client, LOG);
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
