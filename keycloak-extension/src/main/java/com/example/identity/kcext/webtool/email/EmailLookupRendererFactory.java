package com.example.identity.kcext.webtool.email;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to frontend/src/tools/email/index.tsx's {@code authEmailLookup} module -
 * "auth" asks for the email address, "codeInput" asks for the confirmation code.
 */
public class EmailLookupRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-email-lookup";
    private static final Set<String> SUPPORTED_STEPS = Set.of("auth", "codeInput");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override

    public int version() {

        return 1;

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
                .createForm(template());
    }
}
