package com.example.identity.kcext.webtool.identeid;

import com.example.identity.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to ident-eid, whose single "input" step never changes
 * (docs/06-ablaeufe.md #6). The two pages are this renderer's choice, as in the React form: the
 * card data while {@code missingFields} names any of it, then the PIN. A failed attempt shows its
 * page again. Assigning the identity to a register person is ident-kvnr (ADR-18).
 */
public class IdentEidRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-eid";

    /** What the first page collects - everything the backend stages before it asks for the PIN. */
    static final Set<String> CARD_FIELDS = Set.of(
            "familyName", "givenNames", "birthDate", "streetAddress", "postalCode", "locality", "restrictedId");

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
        return "tool-ident-eid.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"input".equals(ctx.step())) return null;
        return form
                .setAttribute("cardPage", cardPage(ctx))
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm(template());
    }

    /** Without {@code missingFields} (a failed attempt) the page it was sent from; the card if unknown. */
    static boolean cardPage(WebToolRenderContext ctx) {
        JsonNode missing = ctx.stepData().get("missingFields");
        if (missing == null) {
            return ctx.submittedFields().isEmpty() || ctx.submittedFields().stream().anyMatch(CARD_FIELDS::contains);
        }
        for (JsonNode field : missing) {
            if (CARD_FIELDS.contains(field.asText())) return true;
        }
        return false;
    }
}
