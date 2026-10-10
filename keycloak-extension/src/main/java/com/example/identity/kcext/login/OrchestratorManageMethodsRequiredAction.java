package com.example.identity.kcext.login;

import com.example.identity.kcext.client.KcTexts;
import com.example.identity.kcext.client.OrchestratorClient;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.jboss.logging.Logger;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.List;
import java.util.function.Supplier;
import java.util.Set;

/**
 * Web-Kanal-Selbstbedienung "Anmeldeverfahren verwalten" (docs/05-api.md, "Anmeldeverfahren
 * verwalten im Web-Kanal"), reached via {@code kc_action=orchestrator-manage-methods} after the
 * login flow; Resume already brought the channel to {@code AUTHENTICATED}. Shows the active-methods
 * list with add, change and remove, and returns to it with a status line after each action. Has its own
 * small dispatch loop, because {@link RequiredActionContext} differs too much from
 * {@code AuthenticationFlowContext}; only {@link WebFormRenderer} is shared.
 */
public class OrchestratorManageMethodsRequiredAction implements RequiredActionProvider {

    static final String PROVIDER_ID = "orchestrator-manage-methods";

    /** "add", "remove" or "change": which action the current sub-journey serves, for the status line. */
    private static final String PENDING_ACTION = "orchestrator_manage_pending_action";

    private static final Logger LOG = Logger.getLogger(OrchestratorManageMethodsRequiredAction.class);

    private final OrchestratorClient client;

    OrchestratorManageMethodsRequiredAction(OrchestratorClient client) {
        this.client = client;
    }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        // Never auto-triggered, only reached via kc_action.
    }

    /**
     * Without this, Keycloak rejects {@code kc_action=orchestrator-manage-methods} with
     * {@code kc_action_status=error} before this provider is called.
     */
    @Override
    public InitiatedActionSupport initiatedActionSupport() {
        return InitiatedActionSupport.SUPPORTED;
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            String channelSessionId = OrchestratorNotes.channelSessionId(authSession);
            // A return from outside (ADR-47) is a GET on the action URL with the tool's input in the
            // query; Keycloak answers a GET of a required action with its challenge.
            if ("tool".equals(authSession.getAuthNote(OrchestratorNotes.PENDING_KIND))
                    && !OrchestratorNextDispatch.withQueryParams(null, context.getUriInfo().getQueryParameters()).isEmpty()) {
                submitTool(context, channelSessionId, new MultivaluedHashMap<>());
                return;
            }
            renderList(context, channelSessionId, null);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.warnf("getMethods failed: %s", e.getMessage());
            context.challenge(WebFormRenderer.errorForm(context.getSession(), context.form(), context.getAuthenticationSession(),
                    KcTexts.of(context.getSession(), "Methodenverwaltung derzeit nicht möglich.")));
        } catch (Exception e) {
            LOG.error("OrchestratorManageMethodsRequiredAction.requiredActionChallenge failed", e);
            context.failure();
        }
    }

    @Override
    public void processAction(RequiredActionContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            String channelSessionId = OrchestratorNotes.channelSessionId(authSession);
            MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
            String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);

            if ("list".equals(pendingKind)) {
                if ("done".equals(form.getFirst("action"))) {
                    // This runs after the flow's end-of-flow hook: a step-up made here reaches the
                    // session only through a report of its own (ADR-59). The report is idempotent.
                    OrchestratorNotes.reportFlowEnd(context.getSession(), authSession, client, LOG);
                    context.success();
                    return;
                }
                if ("add".equals(form.getFirst("action"))) {
                    authSession.setAuthNote(PENDING_ACTION, "add");
                    handleResponse(context, client.startEnrollments(channelSessionId), true, false, Set.of());
                    return;
                }
                String changeInstanceId = form.getFirst("changeMethodInstanceId");
                if (changeInstanceId != null && !changeInstanceId.isBlank()) {
                    authSession.setAuthNote(PENDING_ACTION, "change");
                    handleResponse(context, client.changeMethod(channelSessionId, changeInstanceId), true, false, Set.of());
                    return;
                }
                String methodInstanceId = form.getFirst("removeMethodInstanceId");
                if (methodInstanceId != null && !methodInstanceId.isBlank()) {
                    authSession.setAuthNote(PENDING_ACTION, "remove");
                    handleResponse(context, client.deactivateMethod(channelSessionId, methodInstanceId), true, false, Set.of());
                    return;
                }
                renderList(context, channelSessionId, null);
                return;
            }

            OrchestratorClient.ChannelResponse response;
            if ("select".equals(pendingKind)) {
                if ("true".equals(form.getFirst("orchestrator_abandon"))) {
                    // Either journey (step-up or candidate choice) resolves back to AUTHENTICATED, so
                    // this lands on the list. Not via handleResponse: its "hinzugefügt"/"entfernt"
                    // would report the abandoned attempt as completed.
                    client.abandonJourney(channelSessionId);
                    renderList(context, channelSessionId, KcTexts.of(context.getSession(), "Abgebrochen."));
                    return;
                }
                String selectedToolId = ToolSteps.selectedTool(form);
                if (selectedToolId == null) {
                    context.challenge(WebFormRenderer.errorForm(context.getSession(), context.form(), authSession, KcTexts.of(context.getSession(), "Bitte eine Methode auswählen.")));
                    return;
                }
                response = ToolSteps.activate(context.getSession(), client, channelSessionId, selectedToolId, actionUrl(context));
                handleResponse(context, response, false, false, Set.of());
                return;
            } else if ("confirm".equals(pendingKind)) {
                String answer = ToolSteps.answer(form);
                if (answer == null) {
                    context.challenge(WebFormRenderer.errorForm(context.getSession(), context.form(), authSession, KcTexts.of(context.getSession(), "Bitte eine Antwort auswählen.")));
                    return;
                }
                response = client.answer(channelSessionId, answer);
                handleResponse(context, response, false, "decline".equals(answer), Set.of());
                return;
            } else {
                submitTool(context, channelSessionId, form);
            }
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.infof("Orchestrator tool call failed: %s", e.getMessage());
            context.challenge(WebFormRenderer.errorForm(context.getSession(), context.form(), context.getAuthenticationSession(),
                    e.message(context.getSession()) != null ? e.message(context.getSession()) : KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
        } catch (Exception e) {
            LOG.error("OrchestratorManageMethodsRequiredAction.processAction failed", e);
            context.failure();
        }
    }

    /** Posts the shown tool's step, as {@link ToolSteps#submit}; without a tool session there is nothing to post. */
    private void submitTool(RequiredActionContext context, String channelSessionId, MultivaluedMap<String, String> form) throws Exception {
        ToolSteps.Submitted submitted = ToolSteps.submit(context.getSession(), client, channelSessionId, context.getAuthenticationSession(),
                form, context.getUriInfo().getQueryParameters(), actionUrl(context));
        if (submitted == null) {
            renderList(context, channelSessionId, null);
            return;
        }
        boolean toolAbandoned = "true".equals(submitted.form().getFirst("orchestrator_abandon"));
        handleResponse(context, submitted.response(), false, toolAbandoned, submitted.form().keySet());
    }

    /** A fresh action URL of this step: Keycloak's action code is single-use. */
    private static Supplier<String> actionUrl(RequiredActionContext context) {
        return () -> context.getActionUrl(context.generateCode()).toString();
    }

    private void renderList(RequiredActionContext context, String channelSessionId, String notice) throws Exception {
        List<OrchestratorClient.MethodView> methods = client.getMethods(channelSessionId);
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "list");
        context.challenge(WebFormRenderer.methodsListForm(context.getSession(), context.form(), authSession, methods, notice));
    }

    /**
     * @param firstCall true right after {@code startEnrollments}/{@code deactivateMethod}, before any
     *                  step rendered: tells "nothing was available" from "a sub-journey finished",
     *                  which end in the same {@code next=null} response.
     * @param aborted   true when the user backed out (tool "Abbrechen" or a declined prompt); an
     *                  abort can also end in {@code next=null} and must not read as completed.
     * @param submittedFields the fields of the post this response answers, for the tool page.
     */
    private void handleResponse(RequiredActionContext context, OrchestratorClient.ChannelResponse response, boolean firstCall, boolean aborted,
            Set<String> submittedFields) throws Exception {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String channelSessionId = OrchestratorNotes.channelSessionId(authSession);

        OrchestratorNotes.applyAuthData(authSession, response);

        // No channelState=="AUTHENTICATED" shortcut as in OrchestratorAuthenticator: here the
        // channel is AUTHENTICATED before MANAGE_AUTH_METHODS starts, so it would fire on the first
        // response.
        OrchestratorClient.Next next = response.next();
        if (next == null || next.isAuthenticated()) {
            String action = authSession.getAuthNote(PENDING_ACTION);
            String notice = aborted
                    ? KcTexts.of(context.getSession(), "Abgebrochen.")
                    : "add".equals(action)
                        ? (firstCall ? KcTexts.of(context.getSession(), "Keine weiteren Anmeldeverfahren verfügbar.") : KcTexts.of(context.getSession(), "Anmeldeverfahren hinzugefügt."))
                        : "remove".equals(action) ? KcTexts.of(context.getSession(), "Anmeldeverfahren entfernt.")
                        : "change".equals(action) ? KcTexts.of(context.getSession(), "Anmeldeverfahren geändert.") : null;
            renderList(context, channelSessionId, notice);
            return;
        }

        OrchestratorNextDispatch.Outcome outcome = OrchestratorNextDispatch.classify(next, response);
        if (outcome instanceof OrchestratorNextDispatch.Select select) {
            List<String> options = select.options();
            if (options.isEmpty()) {
                renderList(context, channelSessionId, KcTexts.of(context.getSession(), "Keine weiteren Anmeldeverfahren verfügbar."));
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "select");
            context.challenge(WebFormRenderer.selectForm(context.getSession(), context.form(), authSession, options, response, false));
            return;
        }

        if (outcome instanceof OrchestratorNextDispatch.Tool tool) {
            if (tool.autoActivate()) {
                // Single-candidate auto-activation, as in OrchestratorAuthenticator.
                try {
                    OrchestratorClient.ChannelResponse activated = ToolSteps.activate(context.getSession(), client, channelSessionId,
                            tool.next().toolId(), actionUrl(context));
                    handleResponse(context, activated, false, false, submittedFields);
                } catch (OrchestratorClient.OrchestratorApiException e) {
                    LOG.warnf("Auto-activation of '%s' failed: %s", tool.next().toolId(), e.getMessage());
                    context.challenge(WebFormRenderer.errorForm(context.getSession(), context.form(), authSession, KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
                }
                return;
            }
            ToolSteps.remember(authSession, tool.next());
            context.challenge(WebFormRenderer.toolForm(context.getSession(), context.form(), authSession, tool.next(), response, submittedFields));
            return;
        }

        if (outcome instanceof OrchestratorNextDispatch.Confirm confirm) {
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "confirm");
            context.challenge(WebFormRenderer.confirmForm(context.getSession(), context.form(), authSession, confirm.prompt()));
            return;
        }

        OrchestratorNextDispatch.Unhandled unhandled = (OrchestratorNextDispatch.Unhandled) outcome;
        LOG.warnf("Unhandled orchestrator next: type=%s step=%s", unhandled.next().type(), unhandled.next().step());
        context.failure();
    }

    @Override
    public void close() {
    }
}
