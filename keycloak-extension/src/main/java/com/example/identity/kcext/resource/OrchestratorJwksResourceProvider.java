package com.example.identity.kcext.resource;

import com.example.identity.kcext.client.OrchestratorSettings;
import com.nimbusds.jose.jwk.JWKSet;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

/**
 * Publishes the peer-auth public key at {@code /realms/{realm}/orchestrator-jwks/.well-known/jwks.json},
 * where the orchestrator's {@code kc.peer-auth.jwks-uri} points (docs/12-entscheidungen.md ADR-7).
 * A key of its own, separate from the realm's token-signing keys.
 */
public class OrchestratorJwksResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    public OrchestratorJwksResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @GET
    @Path(".well-known/jwks.json")
    @Produces(MediaType.APPLICATION_JSON)
    public String jwks() {
        return new JWKSet(OrchestratorSettings.of(session).peerAuthSigningKey().toPublicJWK()).toString();
    }

    @Override
    public void close() {
    }
}
