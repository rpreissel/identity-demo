package com.example.identity.kcext.webtool.password;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/** Web-channel counterpart to frontend/src/tools/password/index.tsx's {@code enrollPasswordTool} module. */
public class PasswordEnrollRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "enroll-password";

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
        return "tool-password-enroll.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"enroll".equals(ctx.step())) return null;
        JsonNode demoPassword = ctx.demo().get("password");
        return form
                .setAttribute("demoPassword", demoPassword != null ? demoPassword.asText() : null)
                // The account already has a password: this run changes it.
                .setAttribute("replaces", ctx.stepData().containsKey("replaces") && ctx.stepData().get("replaces").asBoolean(false))
                .createForm(template());
    }
}
