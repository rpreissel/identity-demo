package com.example.identity.kcext.webtool.invite;

import com.example.identity.kcext.KcText;
import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/**
 * Web-channel page of auth-invite (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md): KVNR, or the
 * Partnernummer without one, and the one-time password from the letter, sent together in one step.
 */
public class AuthInviteRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-invite";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("Einmalkennwort");
    }

    @Override
    public KcText hint() {
        return KcText.t("Mit dem Einmalkennwort aus unserem Brief");
    }

    @Override
    public String template() {
        return "tool-auth-invite.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"auth".equals(ctx.step())) return null;
        return form.setAttribute("demoInvitationsJson", demoInvitationsJson(ctx)).createForm(template());
    }
}
