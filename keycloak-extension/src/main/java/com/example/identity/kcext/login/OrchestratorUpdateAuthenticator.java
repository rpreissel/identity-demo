package com.example.identity.kcext.login;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.webtool.WebToolAvailability;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Placed directly after a native step that proves something itself; reports that proof to the
 * orchestrator at once and renders no form. Config: {@code nativeToolId} names the native method
 * (docs/05-api.md Abschnitt 3). {@code amrSourceId} defaults to the execution id, which stays stable
 * across a browser retry: a refresh, not a new proof.
 */
public class OrchestratorUpdateAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(OrchestratorUpdateAuthenticator.class);

    private final OrchestratorClient client;

    OrchestratorUpdateAuthenticator(OrchestratorClient client) {
        this.client = client;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            String nativeToolId = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("nativeToolId");
            if (nativeToolId == null || nativeToolId.isBlank()) {
                LOG.warn("OrchestratorUpdateAuthenticator has no nativeToolId configured - skipping");
                context.success();
                return;
            }
            String configuredSourceId = context.getAuthenticatorConfig().getConfig().get("amrSourceId");
            String amrSourceId = configuredSourceId != null && !configuredSourceId.isBlank()
                    ? configuredSourceId
                    : context.getExecution().getId();

            OrchestratorNotes.appendNativeAmr(context, nativeToolId, amrSourceId);

            String channelSessionId = OrchestratorNotes.channelSessionId(context);
            // The user can already be known here: a native authenticator ahead of this one may
            // have resolved it (docs/05-api.md Abschnitt 3).
            Long accountId = OrchestratorNotes.accountId(context.getUser());
            // The floor must reach Keycloak's full requested level, not just loa1; otherwise this
            // password report finishes the entry journey before LoA-2 can raise it (see requestedAcr).
            String targetAcr = OrchestratorNotes.requestedAcr(context);

            OrchestratorClient.ChannelResponse response = client.upsertChannel(
                    channelSessionId, accountId, targetAcr, OrchestratorNotes.nativeAmr(context), null, null,
                    WebToolAvailability.renderableToolIds(context.getSession()), null
            );
            OrchestratorNotes.applyAuthData(authSession, response);
            context.success();
        } catch (Exception e) {
            LOG.error("OrchestratorUpdateAuthenticator failed", e);
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        context.success();
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
