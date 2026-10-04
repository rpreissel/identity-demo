package com.example.identity.kcext.webtool.sms;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to the two-step enroll-sms tool, in version 2 (ADR-51): the number comes
 * with the consent, asked for while {@code missingFields} names it.
 */
public class SmsEnrollRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "enroll-sms";
    private static final Set<String> SUPPORTED_STEPS = Set.of("enroll", "tanInput");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String template() {
        return "tool-sms-enroll.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!SUPPORTED_STEPS.contains(ctx.step())) return null;
        JsonNode demoTan = ctx.demo().get("tan");
        return form
                .setAttribute("step", ctx.step())
                .setAttribute("demoTan", demoTan != null ? demoTan.asText() : null)
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                // The account already has a number: this run changes it.
                .setAttribute("replaces", ctx.stepData().containsKey("replaces") && ctx.stepData().get("replaces").asBoolean(false))
                .setAttribute("askConsent", asksConsent(ctx))
                .createForm(template());
    }

    private static boolean asksConsent(WebToolRenderContext ctx) {
        JsonNode missing = ctx.stepData().get("missingFields");
        if (missing == null || !missing.isArray()) return false;
        for (JsonNode field : missing) {
            if ("consent".equals(field.asText())) return true;
        }
        return false;
    }
}
