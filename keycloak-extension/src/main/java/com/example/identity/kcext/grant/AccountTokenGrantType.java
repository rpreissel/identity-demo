package com.example.identity.kcext.grant;

import com.example.identity.kcext.AccountUsers;

import com.example.identity.kcext.OrchestratorNotes;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.OAuthErrorException;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.Constants;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.protocol.oidc.grants.OAuth2GrantTypeBase;
import org.keycloak.services.CorsErrorResponseException;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

import java.text.ParseException;
import java.util.List;
import java.util.Set;

/**
 * Custom OAuth 2.0 grant that mints a Keycloak-signed access token for one account (ADR-9). Only a
 * confidential client marked by {@link AccountTokenGrantClients} may call it; that client is the
 * orchestrator, the sole acr/amr authority, so account, acr and amr come as plain parameters.
 * Modeled after {@code ClientCredentialsGrantType}: no interactive flow, the user session is built
 * directly for the target account.
 */
public class AccountTokenGrantType extends OAuth2GrantTypeBase {

    private static final Logger logger = Logger.getLogger(AccountTokenGrantType.class);

    public static final String GRANT_TYPE = "urn:identity-demo:account-token";
    public static final String ACCOUNT_ID_PARAM = "account_id";
    public static final String ACR_PARAM = "acr";
    /** Comma-separated. */
    public static final String AMR_PARAM = "amr";
    /**
     * The user session an earlier call of this login opened. Absent, the call opens a new one;
     * present, it continues exactly that one or fails (ADR-43).
     */
    public static final String SESSION_ID_PARAM = "session_id";
    public static final String ACCOUNT_ID_ATTRIBUTE = AccountUsers.ACCOUNT_ID_ATTRIBUTE;
    private static final String SESSION_MARKER_NOTE = "identity-demo-account-token-session";


    @Override
    public Response process(Context context) {
        setContext(context);

        if (!AccountTokenGrantClients.isAllowed(client)) {
            event.detail(Details.REASON, "Client not allowed to use " + GRANT_TYPE);
            event.error(Errors.UNAUTHORIZED_CLIENT);
            throw new CorsErrorResponseException(cors, OAuthErrorException.UNAUTHORIZED_CLIENT,
                    "Client not allowed to use this grant type", Response.Status.BAD_REQUEST);
        }

        String accountId = formParams.getFirst(ACCOUNT_ID_PARAM);
        if (accountId == null) {
            return reject("Missing " + ACCOUNT_ID_PARAM);
        }

        UserModel user = findUserByAccountId(accountId);
        if (user == null || !user.isEnabled()) {
            return reject("Unknown or disabled account: " + accountId);
        }

        event.user(user);
        event.detail(Details.USERNAME, user.getUsername());

        String scope = getRequestedScopes();

        RootAuthenticationSessionModel rootAuthSession = new AuthenticationSessionManager(session).createAuthenticationSession(realm, false);
        AuthenticationSessionModel authSession = rootAuthSession.createAuthenticationSession(client);
        authSession.setAuthenticatedUser(user);
        authSession.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
        authSession.setClientNote(OIDCLoginProtocol.ISSUER, Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName()));
        authSession.setClientNote(OIDCLoginProtocol.SCOPE_PARAM, scope);

        // One session per login: the first call opens it, every later one (a step-up) continues it.
        // A session that ended is never replaced here, so the login ends with it.
        String sessionId = formParams.getFirst(SESSION_ID_PARAM);
        UserSessionModel userSession;
        if (sessionId == null || sessionId.isBlank()) {
            userSession = new UserSessionManager(session).createUserSession(
                    authSession.getParentSession().getId(), realm, user, user.getUsername(),
                    clientConnection.getRemoteHost(), "identity-demo-account-token", false, null, null,
                    UserSessionModel.SessionPersistenceState.PERSISTENT);
            userSession.setNote(SESSION_MARKER_NOTE, "true");
        } else {
            userSession = continuedSession(user, sessionId);
            if (userSession == null) {
                return reject("Session " + sessionId + " has ended or is not this login's");
            }
        }
        // The same note keys the Web channel writes, read by OrchestratorAcrAmrMapper. Trusted as
        // sent: only the orchestrator's own client gets here.
        String acr = formParams.getFirst(ACR_PARAM);
        if (acr != null && !acr.isBlank()) {
            userSession.setNote(OrchestratorNotes.USER_SESSION_NOTE_ACR, acr);
        }
        String amr = formParams.getFirst(AMR_PARAM);
        if (amr != null) {
            userSession.setNote(OrchestratorNotes.USER_SESSION_NOTE_AMR, amr);
        }
        event.session(userSession);

        AuthenticationManager.setClientScopesInSession(session, authSession);
        ClientSessionContext clientSessionCtx = TokenManager.attachAuthenticationSession(session, userSession, authSession);
        clientSessionCtx.setAttribute(Constants.GRANT_TYPE, context.getGrantType());
        updateUserSessionFromClientAuth(userSession);

        return createTokenResponse(user, userSession, clientSessionCtx, scope, true, null);
    }

    /**
     * Session {@code sessionId} if it is still valid, belongs to {@code user} and was opened by this grant
     * ({@link #SESSION_MARKER_NOTE}); a Web-channel session or another account's is never continued.
     */
    private UserSessionModel continuedSession(UserModel user, String sessionId) {
        UserSessionModel existing = session.sessions().getUserSession(realm, sessionId);
        if (existing == null || !existing.getUser().getId().equals(user.getId())) return null;
        if (!"true".equals(existing.getNote(SESSION_MARKER_NOTE))) return null;
        return AuthenticationManager.isSessionValid(realm, existing) ? existing : null;
    }

    private UserModel findUserByAccountId(String accountId) {
        return AccountUsers.findByAccountId(session, realm, accountId);
    }

    private Response reject(String reason) {
        event.detail(Details.REASON, reason);
        event.error(Errors.INVALID_REQUEST);
        throw new CorsErrorResponseException(cors, OAuthErrorException.INVALID_GRANT, reason, Response.Status.BAD_REQUEST);
    }

    @Override
    public EventType getEventType() {
        return EventType.LOGIN;
    }

    @Override
    public Set<String> getTokenParameterNames() {
        return Set.of(ACCOUNT_ID_PARAM, ACR_PARAM, AMR_PARAM, SESSION_ID_PARAM);
    }

    // Keycloak's refresh_token grant renews the token without coming back here while acr/amr stay
    // the same (docs/12-entscheidungen.md ADR-9). Refreshing also keeps the session's idle timeout alive.
    @Override
    protected boolean useRefreshToken() {
        return true;
    }
}
