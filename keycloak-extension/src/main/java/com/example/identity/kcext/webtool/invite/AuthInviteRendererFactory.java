package com.example.identity.kcext.webtool.invite;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/**
 * Web-channel page of auth-invite-lookup (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md): KVNR, or the
 * Partnernummer without one, and the one-time password from the letter, sent together in one step.
 */
public class AuthInviteRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-invite-lookup";

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
        return "tool-auth-invite.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"auth".equals(ctx.step())) return null;
        return form.setAttribute("demoInvitationsJson", demoInvitationsJson(ctx)).createForm(template());
    }
}
