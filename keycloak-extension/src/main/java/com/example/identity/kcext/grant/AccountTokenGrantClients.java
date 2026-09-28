package com.example.identity.kcext.grant;

import org.keycloak.models.ClientModel;

/**
 * Which clients may call {@link AccountTokenGrantType}: only a confidential client the realm marks
 * with {@link #ALLOWED_CLIENT_ATTRIBUTE} (the orchestrator's own). Without the check any client,
 * the public browser client included, could call the grant.
 */
public final class AccountTokenGrantClients {

    public static final String ALLOWED_CLIENT_ATTRIBUTE = "identity-demo.account-token-grant";

    private AccountTokenGrantClients() {
    }

    /** A public client can prove nothing about itself, so the attribute alone is never enough. */
    public static boolean isAllowed(ClientModel client) {
        return client != null && !client.isPublicClient() && "true".equals(client.getAttribute(ALLOWED_CLIENT_ATTRIBUTE));
    }
}
