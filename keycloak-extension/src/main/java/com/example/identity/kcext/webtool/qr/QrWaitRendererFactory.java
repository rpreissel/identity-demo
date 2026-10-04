package com.example.identity.kcext.webtool.qr;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Shared rendering for {@code auth-qr}/{@code auth-qr-lookup} (docs/05-api.md, Peer-Login
 * bestätigen): {@code waitForApp} shows QR and pairing code until the app decides; {@code enterCode}
 * takes the confirmation code the app shows, and only then is this browser logged in. While waiting,
 * the page asks {@code statusUrl} in the background and submits only once something changed (ADR-45).
 */
abstract class QrWaitRendererFactory extends AbstractWebToolRendererFactory {

    @Override

    public int version() {

        return 1;

    }


    @Override
    public String template() {
        return "tool-qr-wait.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if ("enterCode".equals(ctx.step())) {
            return form.setAttribute("step", "enterCode").createForm(template());
        }
        if (!"waitForApp".equals(ctx.step())) return null;

        JsonNode pairingCodeNode = ctx.stepData().get("pairingCode");
        if (pairingCodeNode == null) return null;
        String pairingCode = pairingCodeNode.asText();

        // /app/ (not /?...) so the link lands directly in the App-Kanal's own app instead of the
        // Willkommen page (docs/10-frontend.md #1); intent=confirm_peer_login is the same wire
        // vocabulary AuthIntent.fromRequest already accepts on POST /app/channels, just carried via
        // the URL instead of a request body (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN).
        String deepLink = ctx.settings().publicOrchestratorBaseUrl() + "/app/?intent=confirm_peer_login&pairingCode="
                + URLEncoder.encode(pairingCode, StandardCharsets.UTF_8);

        return form
                .setAttribute("step", "waitForApp")
                .setAttribute("pairingCode", pairingCode)
                .setAttribute("deepLink", deepLink)
                .setAttribute("qrDataUri", QrImageEncoder.dataUri(deepLink))
                .setAttribute("statusUrl", ctx.statusUrl())
                .createForm(template());
    }
}
