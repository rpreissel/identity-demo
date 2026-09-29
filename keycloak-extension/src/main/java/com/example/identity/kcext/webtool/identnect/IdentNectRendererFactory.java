package com.example.identity.kcext.webtool.identnect;

import com.example.identity.kcext.client.KcText;
import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Web-channel counterpart to ident-nect (docs/adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md): one step {@code redirect}. The
 * page shows "Weiter zu Nect", a link to Nect's jump page; Nect sends the user back to the action
 * URL of this very step, which this factory named at activation, with {@code ?nectCaseId=...}
 * appended. The authenticator hands the query on as the tool's input. After a failed attempt the
 * step data carries no jump URL any more; the page then offers a fresh case ({@code retry}).
 */
public class IdentNectRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-nect";

    /** What the activation body carries: where Nect sends the user back to. */
    static final String RETURN_URI = "returnUri";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("Nect");
    }

    @Override
    public KcText hint() {
        return KcText.t("Ausweis, Reisepass oder EUDI-Wallet bei Nect (simuliert)");
    }

    @Override
    public String template() {
        return "tool-ident-nect.ftl";
    }

    @Override
    public Map<String, String> activationFields(Supplier<String> actionUrl) {
        return Map.of(RETURN_URI, actionUrl.get());
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"redirect".equals(ctx.step())) return null;
        String jumpUrl = jumpUrl(ctx);
        if (jumpUrl != null) form.setAttribute("jumpUrl", jumpUrl);
        return form.createForm(template());
    }

    /**
     * The jump page as the browser reaches it. The orchestrator names it relative to itself
     * ({@code /nect/?case=...}); this page is served by Keycloak, so it gets the orchestrator's public
     * origin in front. Null after a failed attempt: the step data is then the failure, not a case.
     */
    static String jumpUrl(WebToolRenderContext ctx) {
        JsonNode node = ctx.stepData().get("jumpUrl");
        if (node == null || node.isNull()) return null;
        String jumpUrl = node.asText();
        if (jumpUrl.startsWith("/") && ctx.settings() != null) {
            return ctx.settings().publicOrchestratorBaseUrl() + jumpUrl;
        }
        return jumpUrl;
    }
}
