package com.example.identity.kcext.webtool.sms;

import com.example.identity.kcext.KcText;
import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/** Web-channel counterpart to the two-step auth-sms-lookup tool. */
public class SmsLookupRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-sms-lookup";
    private static final Set<String> SUPPORTED_STEPS = Set.of("auth", "tanInput");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("SMS");
    }

    @Override
    public KcText hint() {
        return KcText.t("E-Mail-Adresse + SMS-Code");
    }

    @Override
    public String template() {
        return "tool-sms-lookup.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!SUPPORTED_STEPS.contains(ctx.step())) return null;
        JsonNode demoTan = ctx.demo().get("tan");
        return form
                .setAttribute("step", ctx.step())
                .setAttribute("demoTan", demoTan != null ? demoTan.asText() : null)
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm(template());
    }
}
