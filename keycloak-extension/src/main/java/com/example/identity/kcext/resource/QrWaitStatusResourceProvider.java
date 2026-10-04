package com.example.identity.kcext.resource;

import com.example.identity.kcext.webtool.WebToolAvailability;
import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.client.OrchestratorSettings;
import com.example.identity.kcext.login.OrchestratorNotes;
import com.example.identity.kcext.webtool.qr.AuthQrLookupRendererFactory;
import com.example.identity.kcext.webtool.qr.AuthQrRendererFactory;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * {@code /realms/{realm}/orchestrator-qr/status}: tells the QR waiting page whether it still waits
 * for the app, so the page submits its form only once something changed
 * (docs/adr/ADR-045-qr-warteseite-fragt-im-hintergrund.md). It reads and never advances the flow.
 * Only the holder of the authentication session's cookie gets an answer, and no CORS header opens it
 * to other origins.
 */
public class QrWaitStatusResourceProvider implements RealmResourceProvider {

    private static final Logger LOG = Logger.getLogger(QrWaitStatusResourceProvider.class);

    /** The tools whose waiting page asks here, and the step it shows while waiting. */
    private static final Set<String> QR_TOOLS = Set.of(AuthQrRendererFactory.PROVIDER_ID, AuthQrLookupRendererFactory.PROVIDER_ID);
    private static final String WAITING_STEP = "waitForApp";

    /** {@code ready} means: submit the form now, Keycloak shows what comes next. */
    enum State {
        WAITING("waiting"), READY("ready");

        final String wire;

        State(String wire) {
            this.wire = wire;
        }
    }

    /** The orchestrator's read of a running tool; only {@code next} matters here. */
    @FunctionalInterface
    interface ToolReader {
        OrchestratorClient.Next read(String channelSessionId, String toolSessionId, String toolId) throws Exception;
    }

    private final KeycloakSession session;

    public QrWaitStatusResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @GET
    @Path("status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@QueryParam("client_id") String clientId, @QueryParam("tab_id") String tabId) {
        RealmModel realm = session.getContext().getRealm();
        AuthenticationSessionModel authSession = currentAuthSession(realm, clientId, tabId, (client, tab) ->
                new AuthenticationSessionManager(session).getCurrentAuthenticationSession(realm, client, tab));
        return toResponse(answer(authSession, (channel, toolSession, tool) ->
                OrchestratorSettings.of(session).newClient()
                        .readTool(channel, toolSession, tool, WebToolAvailability.versionOf(session, tool)).next()));
    }

    /**
     * The same lookup Keycloak's own login pages use: the signed {@code AUTH_SESSION_ID} cookie names
     * the root session ({@code byCookie}), client and tab pick the flow run in it. Null without any
     * of them.
     */
    static AuthenticationSessionModel currentAuthSession(RealmModel realm, String clientId, String tabId,
            BiFunction<ClientModel, String, AuthenticationSessionModel> byCookie) {
        if (clientId == null || clientId.isBlank() || tabId == null || tabId.isBlank()) return null;
        ClientModel client = realm.getClientByClientId(clientId);
        if (client == null) return null;
        return byCookie.apply(client, tabId);
    }

    /** Status and body of the answer; {@code body} is null for a refusal. */
    record Answer(int status, String body) {
    }

    /** Without an authentication session there is nothing to tell: 404, no body. */
    static Answer answer(AuthenticationSessionModel authSession, ToolReader reader) {
        if (authSession == null) return new Answer(404, null);
        return new Answer(200, "{\"state\":\"" + state(authSession, reader).wire + "\"}");
    }

    static Response toResponse(Answer answer) {
        CacheControl noStore = new CacheControl();
        noStore.setNoStore(true);
        Response.ResponseBuilder builder = Response.status(answer.status()).cacheControl(noStore);
        if (answer.body() != null) builder.entity(answer.body()).type(MediaType.APPLICATION_JSON_TYPE);
        return builder.build();
    }

    /**
     * Waiting only while the orchestrator still names this very tool session in its waiting step.
     * Anything else, a failed read included, is {@code ready}: the form post then lets the flow
     * report what happened.
     */
    static State state(AuthenticationSessionModel authSession, ToolReader reader) {
        String toolId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_ID);
        String toolSessionId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID);
        String channelSessionId = authSession.getAuthNote(OrchestratorNotes.CHANNEL_SESSION_ID);
        if (!"tool".equals(authSession.getAuthNote(OrchestratorNotes.PENDING_KIND))
                || !QR_TOOLS.contains(toolId) || toolSessionId == null || channelSessionId == null) {
            return State.READY;
        }
        try {
            OrchestratorClient.Next next = reader.read(channelSessionId, toolSessionId, toolId);
            boolean waiting = next != null && next.isTool() && toolId.equals(next.toolId())
                    && toolSessionId.equals(next.toolSessionId()) && WAITING_STEP.equals(next.step());
            return waiting ? State.WAITING : State.READY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return State.READY;
        } catch (Exception e) {
            LOG.debugf("Reading %s failed: %s", toolId, e.getMessage());
            return State.READY;
        }
    }

    /** The address the waiting page of {@code authSession} asks, absolute on Keycloak's frontend URL. */
    public static String statusUrl(KeycloakSession session, AuthenticationSessionModel authSession) {
        String path = session.getContext().getUri().getBaseUriBuilder()
                .path("realms").path(authSession.getRealm().getName())
                .path(QrWaitStatusResourceProviderFactory.ID).path("status")
                .build().toString();
        return path + "?client_id=" + URLEncoder.encode(authSession.getClient().getClientId(), StandardCharsets.UTF_8)
                + "&tab_id=" + URLEncoder.encode(authSession.getTabId(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
    }
}
