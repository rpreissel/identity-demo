package com.example.identity.kcext.grant;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.protocol.oidc.grants.OAuth2GrantType;
import org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory;

/** Registers {@link AccountTokenGrantType} as Keycloak's pluggable oauth2-grant-type SPI. */
public class AccountTokenGrantTypeFactory implements OAuth2GrantTypeFactory {

    @Override
    public String getId() {
        return AccountTokenGrantType.GRANT_TYPE;
    }

    // Exactly 2 chars: Keycloak packs three 2-char shortcuts into a 6-char token-id prefix, and
    // any other length breaks every token with "Incorrect token id".
    @Override
    public String getShortcut() {
        return "at";
    }

    @Override
    public OAuth2GrantType create(KeycloakSession session) {
        return new AccountTokenGrantType();
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
