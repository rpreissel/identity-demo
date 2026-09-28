package com.example.identity.kcext;

import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.example.identity.kcext.webtool.WebToolRenderer;
import com.example.identity.kcext.webtool.WebToolRendererFactory;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Page building for {@link OrchestratorAuthenticator} and {@link OrchestratorManageMethodsRequiredAction}:
 * which template to pick and which attributes to set. Takes {@code session}/{@code form}/
 * {@code authSession} directly, since the two callers' contexts share no supertype; each keeps its
 * own dispatch loop.
 */
final class WebFormRenderer {

    /**
     * The page's own heading. Not {@code title}: Keycloak's form provider overwrites that on every
     * page with the realm's login title.
     */
    private static final String PAGE_TITLE = "pageTitle";

    /** The orchestrator's own pages - also the keys of the Keycloakify theme's per-page texts. */
    private static final String SELECT_PAGE = "orchestrator-select.ftl";
    private static final String TOOL_PAGE = "orchestrator-tool.ftl";
    private static final String CONFIRM_PAGE = "orchestrator-confirm.ftl";
    private static final String ERROR_PAGE = "orchestrator-error.ftl";
    private static final String MANAGE_METHODS_PAGE = "orchestrator-manage-methods.ftl";

    private WebFormRenderer() {
    }

    /**
     * [offerRegistration]: render Keycloak's registration link below the choices. {@code response}
     * is null on a retry path; the page then shows a generic heading.
     */
    static Response selectForm(KeycloakSession session, LoginFormsProvider form, AuthenticationSessionModel authSession,
            List<String> options, OrchestratorClient.ChannelResponse response, String error, boolean offerRegistration) {
        Map<String, String> optionLabels = new LinkedHashMap<>();
        for (String option : options) {
            WebToolRendererFactory factory = rendererFactoryFor(session, option);
            if (factory != null) optionLabels.put(option, KcTexts.resolve(session, factory.title()));
        }
        // The backend names each selection screen (docs/04-orchestrierung.md #4); these are
        // different screens and must not collapse into one generic heading.
        String backendTitle = response != null ? OrchestratorTexts.resolve(session, response.stepData().get("title")) : null;
        String title = backendTitle != null ? backendTitle : KcTexts.of(session, "Anmeldemethode wählen");
        String description = response != null ? OrchestratorTexts.resolve(session, response.stepData().get("description")) : null;
        var built = withTexts(session, form, SELECT_PAGE)
                .setAuthenticationSession(authSession)
                .setAttribute(PAGE_TITLE, title)
                .setAttribute("description", description)
                .setAttribute("options", options)
                .setAttribute("optionLabels", optionLabels)
                .setAttribute("offerRegistration", offerRegistration);
        if (error != null) built.setError(error);
        return built.createForm(SELECT_PAGE);
    }

    static Response toolForm(KeycloakSession session, LoginFormsProvider form, AuthenticationSessionModel authSession,
            OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response, String error) {
        return toolForm(session, form, authSession, next, response, error, Set.of());
    }

    static Response toolForm(KeycloakSession session, LoginFormsProvider form, AuthenticationSessionModel authSession,
            OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response, String error,
            Set<String> submittedFields) {
        String stepError = OrchestratorTexts.resolve(session, response.stepDataError());
        String effectiveError = error != null ? error : stepError;

        WebToolRenderer renderer = session.getProvider(WebToolRenderer.class, next.toolId());
        if (renderer != null) {
            WebToolRendererFactory factory = rendererFactoryFor(session, next.toolId());
            var built = withTexts(session, form, factory != null ? factory.template() : null)
                    .setAuthenticationSession(authSession)
                    .setAttribute("toolId", next.toolId())
                    .setAttribute(PAGE_TITLE, factory != null ? KcTexts.resolve(session, factory.title()) : next.toolId())
                    .setAttribute("hint", factory != null ? KcTexts.resolve(session, factory.hint()) : "");
            if (effectiveError != null) built.setError(effectiveError);
            WebToolRenderContext ctx = new WebToolRenderContext(
                    next.toolId(), next.step(), response.stepData(), response.demo(), effectiveError,
                    OrchestratorSettings.of(session), submittedFields,
                    QrWaitStatusResourceProvider.statusUrl(session, authSession)
            );
            Response rendered = renderer.render(built, ctx);
            if (rendered != null) return rendered;
        }

        // Generic fallback for every toolId without its own WebToolRenderer: one text input per
        // "missingFields" entry - the submitted body must use exactly those field names.
        Map<String, String> fields = new LinkedHashMap<>();
        var missingFields = response.stepData().get("missingFields");
        if (missingFields != null && missingFields.isArray()) {
            missingFields.forEach(fieldName -> fields.put(fieldName.asText(), ""));
        }
        var built = withTexts(session, form, TOOL_PAGE)
                .setAuthenticationSession(authSession)
                .setAttribute("toolId", next.toolId())
                .setAttribute("fields", fields);
        if (effectiveError != null) built.setError(effectiveError);
        return built.createForm(TOOL_PAGE);
    }

    /**
     * The generic yes/no prompt (next.context=prompt, next.step=confirm, stepData.prompt).
     * {@code prompt} is null on a retry path, as in {@link #toolForm}.
     */
    static Response confirmForm(KeycloakSession session, LoginFormsProvider form, AuthenticationSessionModel authSession, JsonNode prompt, String error) {
        String title = orDefault(prompt != null ? OrchestratorTexts.resolve(session, prompt.get("title")) : null, KcTexts.of(session, "Bestätigung erforderlich"));
        String confirmLabel = orDefault(prompt != null ? OrchestratorTexts.resolve(session, prompt.get("confirmLabel")) : null, KcTexts.of(session, "Ja"));
        String cancelLabel = orDefault(prompt != null ? OrchestratorTexts.resolve(session, prompt.get("cancelLabel")) : null, KcTexts.of(session, "Nein"));
        var built = withTexts(session, form, CONFIRM_PAGE)
                .setAuthenticationSession(authSession)
                .setAttribute(PAGE_TITLE, title)
                .setAttribute("confirmLabel", confirmLabel)
                .setAttribute("cancelLabel", cancelLabel);
        if (error != null) built.setError(error);
        return built.createForm(CONFIRM_PAGE);
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    static Response errorForm(KeycloakSession session, LoginFormsProvider form, AuthenticationSessionModel authSession, String message) {
        return withTexts(session, form, ERROR_PAGE).setAuthenticationSession(authSession).setError(message).createForm(ERROR_PAGE);
    }

    /**
     * The "manage" landing screen: active methods, each with "Entfernen", plus "Neues Verfahren
     * hinzufügen" and "Fertig". Rows are plain maps: FreeMarker sees Bean getters, not record
     * accessors, and would render a {@code MethodView} as blank fields.
     */
    static Response methodsListForm(KeycloakSession session, LoginFormsProvider form, AuthenticationSessionModel authSession,
            List<OrchestratorClient.MethodView> methods, String notice) {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        for (OrchestratorClient.MethodView m : methods) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", m.id());
            row.put("method", m.method());
            row.put("label", m.label());
            rows.add(row);
        }
        var built = withTexts(session, form, MANAGE_METHODS_PAGE)
                .setAuthenticationSession(authSession)
                .setAttribute("methods", rows);
        if (notice != null) built.setInfo(notice);
        return built.createForm(MANAGE_METHODS_PAGE);
    }

    /**
     * Every orchestrator page gets {@code t} for its German templates, {@code ${t.of("Weiter")}},
     * resolved in the login's language (ADR-33). And {@code texts}, the wordings the Keycloakify
     * page uses, as a plain map: that theme renders in the browser and cannot call {@code t} (ADR-41).
     */
    private static LoginFormsProvider withTexts(KeycloakSession session, LoginFormsProvider form, String template) {
        return form.setAttribute("t", KcTexts.forTemplates(session))
                .setAttribute("texts", KcTexts.forBrowser(session, template));
    }

    private static WebToolRendererFactory rendererFactoryFor(KeycloakSession session, String toolId) {
        return (WebToolRendererFactory) session.getKeycloakSessionFactory()
                .getProviderFactory(WebToolRenderer.class, toolId);
    }
}
