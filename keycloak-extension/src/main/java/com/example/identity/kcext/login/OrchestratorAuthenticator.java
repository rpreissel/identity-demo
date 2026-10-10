package com.example.identity.kcext.login;

import com.example.identity.kcext.client.KcTexts;
import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.federation.KcSubject;
import com.example.identity.kcext.webtool.WebToolAvailability;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The kc facade's Keycloak-side driver (docs/05-api.md Abschnitt 3b). Handles initial login and
 * step-up alike and renders whatever the orchestrator names as {@code next}. Config properties:
 * {@code targetAcr} (this execution's LoA as orchestrator ACR) and {@code intent} (entry intent of a
 * fresh channel, empty means {@code web_select_method}; docs/04-orchestrierung.md #2).
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
            String intent = config(context, "intent");
            String channelSessionId = OrchestratorNotes.channelSessionIdFor(context.getAuthenticationSession(), intent);
            // Taken from context.getUser(), not from an existing UserSessionModel: on a step-up the
            // cookie authenticator already attached the user before this flow run has a session.
            KcSubject subject = KcSubject.of(context.getUser());
            // Keycloak's requested level wins; the static config is the fallback without acr_values.
            String targetAcr = OrchestratorNotes.requestedAcr(context);
            if (targetAcr == null) targetAcr = config(context, "targetAcr");

            // The intent only counts on a channel's first call, so a changed intent gets a fresh
            // channel above (docs/04-orchestrierung.md #2).
            // No kcSessionId here: the resume authenticator names the session once, at the start of the run.
            OrchestratorClient.ChannelResponse response = client.upsertChannel(
                    channelSessionId, subject, targetAcr, null,
                    WebToolAvailability.renderableTools(context.getSession()), intent
            );
            handleResponse(context, response, null);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.warnf("Orchestrator upsertChannel failed: %s", e.getMessage());
            // A refusal carries the orchestrator's reason, e.g. that a process access is not raised (ADR-48).
            String message = ApiFailure.of(e.status()) == ApiFailure.REJECTED ? e.message(context.getSession()) : null;
            context.challenge(errorForm(context, message != null ? message : KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
        } catch (Exception e) {
            LOG.error("OrchestratorAuthenticator.authenticate failed", e);
            unavailable(context);
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
                    String selectedToolId = ToolSteps.selectedTool(form);
                    if (selectedToolId == null) {
                        context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Bitte eine Methode auswählen.")));
                        return;
                    }
                    response = activateTool(context, selectedToolId);
                }
            } else if ("confirm".equals(pendingKind)) {
                String answer = ToolSteps.answer(form);
                if (answer == null) {
                    context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Bitte eine Antwort auswählen.")));
                    return;
                }
                response = client.answer(channelSessionId, answer);
            } else {
                // A return from outside is a GET on the action URL: its query is the tool's input.
                ToolSteps.Submitted submitted = ToolSteps.submit(context.getSession(), client, channelSessionId, authSession,
                        form, context.getUriInfo().getQueryParameters(), actionUrl(context));
                if (submitted == null) {
                    unavailable(context);
                    return;
                }
                response = submitted.response();
                form = submitted.form();
            }
            handleResponse(context, response, form);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.infof("Orchestrator tool call failed: %s", e.getMessage());
            // Never failureChallenge: Keycloak's brute-force protection would book it against the
            // user. The orchestrator counts real attempts itself (docs/adr/ADR-044).
            String message = ApiFailure.of(e.status()) == ApiFailure.REJECTED ? e.message(context.getSession()) : null;
            context.challenge(errorForm(context, message != null ? message : KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
        } catch (Exception e) {
            LOG.error("OrchestratorAuthenticator.action failed", e);
            unavailable(context);
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
                unavailable(context);
                return;
            }
            context.setUser(user);
            authSession.setAuthenticatedUser(user);
        }
        LoginCompletion.Verdict verdict = LoginCompletion.judge(response, knownSubject, config(context, "targetAcr"));
        if (verdict instanceof LoginCompletion.Refuse refuse) {
            LOG.errorf("Orchestrator answer refused for channel %s: %s", response.channelSessionId(), refuse.reason());
            unavailable(context);
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
            List<String> options = select.options();
            if (options.isEmpty()) {
                // A step-up without any way out ends at the orchestrator with its reason (Abort), so
                // an empty selection means the setup offers nothing here: tell the admin, not the user.
                LOG.warnf("Orchestrator offered no method for channel %s; check the tool locks",
                        response.channelSessionId());
                context.challenge(errorForm(context,
                        KcTexts.of(context.getSession(), "Für diese Anmeldung steht gerade kein Verfahren zur Verfügung. Bitte versuchen Sie es später noch einmal.")));
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "select");
            context.challenge(WebFormRenderer.selectForm(context.getSession(), context.form(), authSession, options, response,
                    offersRegistration(context)));
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
                    unavailable(context);
                }
                return;
            }
            ToolSteps.remember(authSession, tool.next());
            context.challenge(WebFormRenderer.toolForm(context.getSession(), context.form(), authSession, tool.next(), response,
                    lastForm == null ? Set.of() : lastForm.keySet()));
            return;
        }

        if (outcome instanceof OrchestratorNextDispatch.Confirm confirm) {
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "confirm");
            context.challenge(WebFormRenderer.confirmForm(context.getSession(), context.form(), authSession, confirm.prompt()));
            return;
        }

        OrchestratorNextDispatch.Unhandled unhandled = (OrchestratorNextDispatch.Unhandled) outcome;
        LOG.warnf("Unhandled orchestrator next: type=%s step=%s", unhandled.next().type(), unhandled.next().step());
        unavailable(context);
    }

    /**
     * The "Registrieren" link appears where Keycloak's own login form would show it: nobody known yet,
     * registration allowed, and not already inside the registration flow.
     */
    private static boolean offersRegistration(AuthenticationFlowContext context) {
        String intent = config(context, "intent");
        return context.getUser() == null
                && context.getRealm().isRegistrationAllowed()
                && !"register".equalsIgnoreCase(intent);
    }

    private OrchestratorClient.ChannelResponse activateTool(AuthenticationFlowContext context, String toolId) throws IOException, InterruptedException {
        return ToolSteps.activate(context.getSession(), client, OrchestratorNotes.channelSessionId(context), toolId, actionUrl(context));
    }

    /** A fresh action URL of this step: Keycloak's action code is single-use. */
    private static Supplier<String> actionUrl(AuthenticationFlowContext context) {
        return () -> context.getActionUrl(context.generateAccessCode()).toString();
    }

    /**
     * Whatever went wrong between Keycloak and the orchestrator, it is not the user's failed attempt.
     * failure() would be: Keycloak books it on the authenticated user for its brute-force protection.
     * The orchestrator counts real attempts itself (docs/adr/ADR-044).
     */
    private void unavailable(AuthenticationFlowContext context) {
        context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
    }

    private Response errorForm(AuthenticationFlowContext context, String message) {
        return WebFormRenderer.errorForm(context.getSession(), context.form(), context.getAuthenticationSession(), message);
    }

    /** This execution's config value [key], or null without a config. */
    private static String config(AuthenticationFlowContext context, String key) {
        return context.getAuthenticatorConfig() == null ? null : context.getAuthenticatorConfig().getConfig().get(key);
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
