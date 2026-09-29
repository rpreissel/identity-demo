package com.example.identity.kcext.webtool.identfsc;

import com.example.identity.kcext.client.KcText;
import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to ident-fsc, whose single "input" step never changes
 * (docs/06-ablaeufe.md #2). The two pages are this renderer's choice, as in the React form: personal
 * data while {@code missingFields} names any of it, then the code. A failed attempt shows its page
 * again.
 */
public class IdentFscRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-fsc";

    /** What the first page collects - everything the backend stages before it asks for fsc. */
    private static final Set<String> PERSONAL_FIELDS = Set.of("kvnr", "familyName", "givenNames", "birthDate");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("Freischaltcode");
    }

    @Override
    public KcText hint() {
        return KcText.t("Persönliche Daten und Freischaltcode");
    }

    @Override
    public String template() {
        return "tool-ident-fsc.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"input".equals(ctx.step())) return null;
        JsonNode missing = ctx.stepData().get("missingFields");
        boolean personalienPage = false;
        if (missing != null) {
            for (JsonNode field : missing) {
                if (PERSONAL_FIELDS.contains(field.asText())) personalienPage = true;
            }
        } else {
            personalienPage = ctx.submittedFields().stream().anyMatch(PERSONAL_FIELDS::contains);
        }
        return form
                .setAttribute("personalienPage", personalienPage)
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm(template());
    }
}
