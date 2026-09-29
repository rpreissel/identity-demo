package com.example.identity.kcext.webtool.password;

import com.example.identity.kcext.client.KcText;
import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/** Web-channel counterpart to frontend/src/tools/password/index.tsx's {@code authPasswordLookup} module. */
public class PasswordLookupRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-password-lookup";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("Passwort");
    }

    @Override
    public KcText hint() {
        return KcText.t("E-Mail-Adresse + Passwort");
    }

    @Override
    public String template() {
        return "tool-password-lookup.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"auth".equals(ctx.step())) return null;
        JsonNode demoPassword = ctx.demo().get("password");
        return form
                .setAttribute("demoPassword", demoPassword != null ? demoPassword.asText() : null)
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm(template());
    }
}
