package com.example.identity.kcext.login;

import com.example.identity.kcext.client.KcTexts;
import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.federation.KcSubject;
import com.example.identity.kcext.webtool.WebToolAvailability;
import com.example.identity.kcext.webtool.WebToolRendererFactory;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The kc facade's Keycloak-side driver (docs/05-api.md Abschnitt 3). Handles initial login and
 * step-up alike and renders whatever the orchestrator names as {@code next}. Config properties:
 * {@code toolId} (static pre-selection of an account-independent tool), {@code targetAcr} (this
 * execution's LoA as orchestrator ACR) and {@code intent} (entry intent of a fresh channel, empty
 * means {@code web_select_method}; docs/04-orchestrierung.md #2).
 */
public class OrchestratorAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(OrchestratorAuthenticator.class);

    private final OrchestratorClient client;

    OrchestratorAuthenticator(OrchestratorClient client) {
        this.client = client;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            // Read up front: the intent also decides which channel this run talks to.
            String intent = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("intent");
            String channelSessionId = OrchestratorNotes.channelSessionIdFor(context.getAuthenticationSession(), intent);
            // Taken from context.getUser(), not from an existing UserSessionModel: on a step-up the
            // cookie authenticator already attached the user before this flow run has a session.
            KcSubject subject = KcSubject.of(context.getUser());
            // Keycloak's requested level wins; the static config is the fallback without acr_values.
            String staticTargetAcr = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("targetAcr");
            String targetAcr = OrchestratorNotes.requestedAcr(context);
            if (targetAcr == null) targetAcr = staticTargetAcr;

            // The intent only counts on a channel's first call, so a changed intent gets a fresh
            // channel above (docs/04-orchestrierung.md #2).
            // No native amr and no restoreData here: Resume and Update report those once, when
            // they happen. Sending them again would only repeat a no-op evidence merge.
            OrchestratorClient.ChannelResponse response = client.upsertChannel(
                    channelSessionId, subject, targetAcr, List.of(), null, null,
                    WebToolAvailability.renderableToolIds(context.getSession()), intent
            );
            handleResponse(context, response, null);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.warnf("Orchestrator upsertChannel failed: %s", e.getMessage());
            // A refusal carries the orchestrator's reason, e.g. that a process access is not raised (ADR-48).
            String message = ApiFailure.of(e.status()) == ApiFailure.REJECTED ? e.message(context.getSession()) : null;
            context.challenge(errorForm(context, message != null ? message : KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
        } catch (Exception e) {
            LOG.error("OrchestratorAuthenticator.authenticate failed", e);
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            String channelSessionId = OrchestratorNotes.channelSessionId(context);

            MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
            String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);

            OrchestratorClient.ChannelResponse response;
            if ("select".equals(pendingKind)) {
                if ("true".equals(form.getFirst("orchestrator_abandon"))) {
                    // "Abbrechen" ends the login, not just the journey: the orchestrator would restart
                    // the same entry intent and show this page again. The client gets
                    // error=access_denied; the kc channel is left to its TTL.
                    context.cancelLogin();
                    return;
                } else {
                    String selectedToolId = form.getFirst("toolId");
                    if (selectedToolId == null || selectedToolId.isBlank()) {
                        context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Bitte eine Methode auswählen.")));
                        return;
                    }
                    response = activateTool(context, selectedToolId);
                }
            } else if ("confirm".equals(pendingKind)) {
                String answer = form.getFirst("orchestrator_answer");
                if (!"accept".equals(answer) && !"decline".equals(answer)) {
                    context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Bitte eine Antwort auswählen.")));
                    return;
                }
                response = client.answer(channelSessionId, answer);
            } else {
                String toolId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_ID);
                String toolSessionId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID);
                if (toolId == null || toolSessionId == null) {
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                    return;
                }
                // A return from outside is a GET on the action URL: its query is the tool's input.
                form = OrchestratorNextDispatch.withQueryParams(form, context.getUriInfo().getQueryParameters());
                WebToolRendererFactory factory = WebFormRenderer.rendererFactoryFor(context.getSession(), toolId);
                if (factory != null) {
                    factory.actionFields(form::getFirst, () -> context.getActionUrl(context.generateAccessCode()).toString())
                            .forEach(form::putSingle);
                }
                response = OrchestratorNextDispatch.dispatchToolAction(client, channelSessionId, toolId, toolSessionId, form);
            }
            handleResponse(context, response, form);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.infof("Orchestrator tool call failed: %s", e.getMessage());
            // Never failureChallenge: Keycloak's brute-force protection would book it against the
            // user. The orchestrator counts real attempts itself (docs/adr/ADR-044).
            if (ApiFailure.of(e.status()) == ApiFailure.REJECTED) {
                context.challenge(currentChallenge(context, e.message(context.getSession())));
            } else {
                context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
            }
        } catch (Exception e) {
            LOG.error("OrchestratorAuthenticator.action failed", e);
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }

    private void handleResponse(AuthenticationFlowContext context, OrchestratorClient.ChannelResponse response, MultivaluedMap<String, String> lastForm) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        KcSubject knownSubject = KcSubject.of(context.getUser());

        if (response.authDataSubject() != null && context.getUser() == null) {
            UserModel user = response.authDataSubject().findUser(context.getSession(), context.getRealm());
            if (user == null) {
                // The orchestrator just named this subject - not finding it is an inconsistency,
                // never a reason to invent a user (Keycloak creates no users).
                LOG.errorf("Orchestrator named %s, but its federation does not know it", response.authDataSubject());
                context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                return;
            }
            context.setUser(user);
            authSession.setAuthenticatedUser(user);
        }
        String certifiedAcr = context.getAuthenticatorConfig() == null ? null
                : context.getAuthenticatorConfig().getConfig().get("targetAcr");
        LoginCompletion.Verdict verdict = LoginCompletion.judge(response, knownSubject, certifiedAcr);
        if (verdict instanceof LoginCompletion.Refuse refuse) {
            LOG.errorf("Orchestrator answer refused for channel %s: %s", response.channelSessionId(), refuse.reason());
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
            return;
        }
        OrchestratorNotes.applyAuthData(authSession, response);
        if (verdict instanceof LoginCompletion.Complete) {
            context.success();
            return;
        }

        OrchestratorClient.Next next = response.next();

        OrchestratorNextDispatch.Outcome outcome = OrchestratorNextDispatch.classify(next, response);
        if (outcome instanceof OrchestratorNextDispatch.Select select) {
            String staticToolId = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("toolId");
            List<String> options = select.options();
            LOG.debugf("Orchestrator method selection: configured toolId='%s', options=%s",
                    staticToolId, options);
            if (staticToolId != null && !staticToolId.isBlank()) {
                try {
                    OrchestratorClient.ChannelResponse activated = activateTool(context, staticToolId);
                    handleResponse(context, activated, lastForm);
                    return;
                } catch (OrchestratorClient.OrchestratorApiException e) {
                    LOG.warnf(e, "Static tool pre-selection '%s' failed", staticToolId);
                    context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Das konfigurierte Anmeldeverfahren ist derzeit nicht verfügbar.")));
                    return;
                } catch (Exception e) {
                    LOG.error("Static tool pre-selection '" + staticToolId + "' failed", e);
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                    return;
                }
            }
            if (options.isEmpty()) {
                context.challenge(errorForm(context,
                        KcTexts.of(context.getSession(), "Kein Anmeldeverfahren verfügbar. Prüfen Sie die Tool-ID der LoA-Execution.")));
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "select");
            context.challenge(selectForm(context, options, response, null));
            return;
        }

        if (outcome instanceof OrchestratorNextDispatch.Tool tool) {
            if (tool.autoActivate()) {
                // An auto-activation only names the next tool; no ToolSession exists yet. Activate
                // it here so PENDING_TOOL_SESSION_ID is never null.
                try {
                    OrchestratorClient.ChannelResponse activated = activateTool(context, tool.next().toolId());
                    handleResponse(context, activated, lastForm);
                } catch (OrchestratorClient.OrchestratorApiException e) {
                    LOG.warnf("Auto-activation of '%s' failed: %s", tool.next().toolId(), e.getMessage());
                    context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
                } catch (Exception e) {
                    LOG.error("Auto-activation of '" + tool.next().toolId() + "' failed", e);
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                }
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "tool");
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_ID, tool.next().toolId());
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID, tool.next().toolSessionId());
            context.challenge(WebFormRenderer.toolForm(context.getSession(), context.form(), authSession, tool.next(), response, null,
                    lastForm == null ? Set.of() : lastForm.keySet()));
            return;
        }

        if (outcome instanceof OrchestratorNextDispatch.Confirm confirm) {
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "confirm");
            context.challenge(WebFormRenderer.confirmForm(context.getSession(), context.form(), authSession, confirm.prompt(), null));
            return;
        }

        OrchestratorNextDispatch.Unhandled unhandled = (OrchestratorNextDispatch.Unhandled) outcome;
        LOG.warnf("Unhandled orchestrator next: type=%s step=%s", unhandled.next().type(), unhandled.next().step());
        context.failure(AuthenticationFlowError.INTERNAL_ERROR);
    }

    /**
     * {@code response} is null on the retry path ({@link #currentChallenge}); the page then shows a
     * generic heading.
     */
    private Response selectForm(AuthenticationFlowContext context, List<String> options, OrchestratorClient.ChannelResponse response, String error) {
        return WebFormRenderer.selectForm(context.getSession(), context.form(), context.getAuthenticationSession(), options, response, error,
                offersRegistration(context));
    }

    /**
     * The "Registrieren" link appears where the native login form would show it: nobody known yet,
     * registration allowed, and not already inside the registration flow.
     */
    private static boolean offersRegistration(AuthenticationFlowContext context) {
        String intent = context.getAuthenticatorConfig() == null ? null
                : context.getAuthenticatorConfig().getConfig().get("intent");
        return context.getUser() == null
                && context.getRealm().isRegistrationAllowed()
                && !"register".equalsIgnoreCase(intent);
    }

    /**
     * Activates a tool with what its renderer asks to send along - for most tools nothing, for one
     * that sends the user away the action URL of this step as the address to come back to.
     */
    private OrchestratorClient.ChannelResponse activateTool(AuthenticationFlowContext context, String toolId) throws IOException, InterruptedException {
        WebToolRendererFactory factory = WebFormRenderer.rendererFactoryFor(context.getSession(), toolId);
        Map<String, String> fields = factory == null ? Map.of()
                : factory.activationFields(() -> context.getActionUrl(context.generateAccessCode()).toString());
        return client.activateTool(OrchestratorNotes.channelSessionId(context), toolId, fields);
    }

    private Response toolForm(AuthenticationFlowContext context, OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response, String error) {
        return WebFormRenderer.toolForm(context.getSession(), context.form(), context.getAuthenticationSession(), next, response, error);
    }

    private Response errorForm(AuthenticationFlowContext context, String message) {
        return WebFormRenderer.errorForm(context.getSession(), context.form(), context.getAuthenticationSession(), message);
    }

    private Response currentChallenge(AuthenticationFlowContext context, String error) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);
        if ("select".equals(pendingKind)) {
            // Only something to show; the next authenticate() pass fetches the current options.
            return errorForm(context, error != null ? error : "Die Auswahl der Anmeldemethode ist fehlgeschlagen.");
        }
        // Through WebFormRenderer like every other page: the template needs t (and Keycloakify texts).
        return errorForm(context, error);
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
