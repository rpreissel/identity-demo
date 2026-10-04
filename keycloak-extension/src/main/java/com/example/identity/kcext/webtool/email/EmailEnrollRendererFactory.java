package com.example.identity.kcext.webtool.email;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/**
 * Web-channel counterpart to {@code enrollEmailTool}. It completes on activation, so there is no
 * page. The factory still declares the tool available here and names it on the selection page.
 */
public class EmailEnrollRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "enroll-email";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    /** No page of its own. */
    @Override
    public int version() {
        return 1;
    }

    @Override
    public String template() {
        return null;
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        return null;
    }
}
