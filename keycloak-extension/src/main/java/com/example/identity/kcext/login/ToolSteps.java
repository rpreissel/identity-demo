package com.example.identity.kcext.login;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.webtool.WebToolAvailability;
import com.example.identity.kcext.webtool.WebToolRendererFactory;
import jakarta.ws.rs.core.MultivaluedMap;
import org.keycloak.models.KeycloakSession;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The tool steps both drivers of a Web channel run alike, {@link OrchestratorAuthenticator} in the
 * login flow and {@link OrchestratorManageMethodsRequiredAction} after it: activating a tool, posting
 * a step, and remembering which tool session the page shows. {@code actionUrl} is the address of the
 * running step, which a tool that sends the user away names as the way back (ADR-47).
 */
final class ToolSteps {

    private ToolSteps() {
    }

    /** A posted tool step: the orchestrator's answer and the fields that went into it. */
    record Submitted(OrchestratorClient.ChannelResponse response, MultivaluedMap<String, String> form) {
    }

    /** Activates [toolId] with what its renderer asks to send along. */
    static OrchestratorClient.ChannelResponse activate(KeycloakSession session, OrchestratorClient client, String channelSessionId,
            String toolId, Supplier<String> actionUrl) throws IOException, InterruptedException {
        WebToolRendererFactory factory = WebFormRenderer.rendererFactoryFor(session, toolId);
        Map<String, String> fields = factory == null ? Map.of() : factory.activationFields(actionUrl);
        return client.activateTool(channelSessionId, toolId, WebToolAvailability.versionOf(session, toolId), fields);
    }

    /**
     * Posts the shown tool's step: the form, plus the query of a return from outside (a GET on the
     * action URL), plus what its renderer adds. Null when the page shows no tool session.
     */
    static Submitted submit(KeycloakSession session, OrchestratorClient client, String channelSessionId, AuthenticationSessionModel authSession,
            MultivaluedMap<String, String> form, MultivaluedMap<String, String> query, Supplier<String> actionUrl) throws IOException, InterruptedException {
        String toolId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_ID);
        String toolSessionId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID);
        if (toolId == null || toolSessionId == null) return null;
        MultivaluedMap<String, String> fields = OrchestratorNextDispatch.withQueryParams(form, query);
        WebToolRendererFactory factory = WebFormRenderer.rendererFactoryFor(session, toolId);
        if (factory != null) factory.actionFields(fields::getFirst, actionUrl).forEach(fields::putSingle);
        OrchestratorClient.ChannelResponse response = OrchestratorNextDispatch.dispatchToolAction(client, channelSessionId, toolId,
                WebToolAvailability.versionOf(session, toolId), toolSessionId, fields);
        return new Submitted(response, fields);
    }

    /** The page now shows [next]'s tool session; the next post goes to it. */
    static void remember(AuthenticationSessionModel authSession, OrchestratorClient.Next next) {
        authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "tool");
        authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_ID, next.toolId());
        authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID, next.toolSessionId());
    }

    /** The chosen tool of a selection page, or null without a choice. */
    static String selectedTool(MultivaluedMap<String, String> form) {
        String toolId = form.getFirst("toolId");
        return toolId == null || toolId.isBlank() ? null : toolId;
    }

    /** The answer of a yes/no page, or null without a valid one. */
    static String answer(MultivaluedMap<String, String> form) {
        String answer = form.getFirst("orchestrator_answer");
        return "accept".equals(answer) || "decline".equals(answer) ? answer : null;
    }
}
