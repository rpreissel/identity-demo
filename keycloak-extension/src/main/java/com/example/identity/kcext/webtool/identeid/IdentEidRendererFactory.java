package com.example.identity.kcext.webtool.identeid;

import com.example.identity.kcext.KcText;
import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to ident-eid (docs/06-ablaeufe.md #6), two steps: "card" (the simulated
 * eID card's data) and "pin". Assigning the identity to a register person is ident-kvnr (ADR-18).
 */
public class IdentEidRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-eid";
    private static final Set<String> SUPPORTED_STEPS = Set.of("card", "pin");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("eID");
    }

    @Override
    public KcText hint() {
        return KcText.t("Online-Ausweisfunktion (simuliert)");
    }

    @Override
    public String template() {
        return "tool-ident-eid.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!SUPPORTED_STEPS.contains(ctx.step())) return null;
        return form
                .setAttribute("step", ctx.step())
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm(template());
    }
}
