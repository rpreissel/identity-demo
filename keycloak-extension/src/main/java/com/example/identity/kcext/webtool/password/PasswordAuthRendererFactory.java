package com.example.identity.kcext.webtool.password;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/** Web-channel counterpart to frontend/src/tools/password/index.tsx's {@code authPassword} module. */
public class PasswordAuthRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-password";

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
        return "tool-password-auth.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"auth".equals(ctx.step())) return null;
        JsonNode demoPassword = ctx.demo().get("password");
        return form
                .setAttribute("demoPassword", demoPassword != null ? demoPassword.asText() : null)
                .createForm(template());
    }
}
