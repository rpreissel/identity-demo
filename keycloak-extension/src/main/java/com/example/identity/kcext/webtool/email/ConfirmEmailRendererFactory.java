package com.example.identity.kcext.webtool.email;

import com.example.identity.kcext.client.KcText;
import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to frontend/src/tools/email/index.tsx's {@code confirmEmailTool}: the
 * address a registration confirms before any login method. Its two pages are the lookup's -
 * address, then code - so it shares {@code tool-email-lookup.ftl}.
 */
public class ConfirmEmailRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "confirm-email";
    private static final Set<String> SUPPORTED_STEPS = Set.of("input", "codeInput");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("E-Mail");
    }

    @Override
    public KcText hint() {
        return KcText.t("E-Mail-Adresse bestätigen");
    }

    @Override
    public String template() {
        return "tool-email-lookup.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!SUPPORTED_STEPS.contains(ctx.step())) return null;
        JsonNode demoTan = ctx.demo().get("tan");
        return form
                .setAttribute("step", ctx.step())
                .setAttribute("demoTan", demoTan != null ? demoTan.asText() : null)
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                // confirm-email takes a new address in every state: "Zurück" on the code shows the
                // address again, in the page - only sending it goes to the server.
                .setAttribute("addressAgain", true)
                .createForm(template());
    }
}
