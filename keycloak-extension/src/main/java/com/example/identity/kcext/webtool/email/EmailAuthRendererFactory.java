package com.example.identity.kcext.webtool.email;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/** Web-channel counterpart to frontend/src/tools/email/index.tsx's {@code authEmail} module. */
public class EmailAuthRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-email";

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
        return "tool-email-auth.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"auth".equals(ctx.step())) return null;
        JsonNode demoTan = ctx.demo().get("tan");
        return form
                .setAttribute("demoTan", demoTan != null ? demoTan.asText() : null)
                .createForm(template());
    }
}
