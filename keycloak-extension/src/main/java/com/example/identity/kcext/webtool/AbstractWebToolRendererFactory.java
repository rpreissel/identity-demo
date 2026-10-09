package com.example.identity.kcext.webtool;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/** Base for a stateless tool factory+renderer pair; the factory hands back itself. */
public abstract class AbstractWebToolRendererFactory implements WebToolRendererFactory, WebToolRenderer {

    /**
     * Raw JSON of {@code demo.persons}, or {@code "null"} when absent. The theme's
     * {@code DemoPersonPicker} parses it.
     */
    protected static String demoPersonsJson(WebToolRenderContext ctx) {
        JsonNode persons = ctx.demo().get("persons");
        return persons != null ? persons.toString() : "null";
    }

    /** The open invitations of the demo (ADR-48), for the picker on the one-time password page. */
    protected static String demoInvitationsJson(WebToolRenderContext ctx) {
        JsonNode invitations = ctx.demo().get("invitations");
        return invitations != null ? invitations.toString() : "null";
    }

    @Override
    public WebToolRenderer create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
