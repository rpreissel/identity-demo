package com.example.identity.kcext.webtool.qr;

import com.example.identity.kcext.client.KcText;
import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/**
 * Web-channel counterpart to {@code EnrollQrForm.tsx}: a pure opt-in with a single confirm button
 * (docs/03-tool-architektur.md). Registering this factory is also what lets the orchestrator offer
 * {@code enroll-qr} in the Web channel.
 */
public class EnrollQrRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "enroll-qr";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("QR-Login");
    }

    @Override
    public KcText hint() {
        return KcText.t("Web-Login per QR-Code erlauben");
    }

    @Override
    public String template() {
        return "tool-qr-enroll.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"enroll".equals(ctx.step())) return null;
        return form.createForm(template());
    }
}
