package com.example.identity.kcext;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.StorageId;

/**
 * Resolves an orchestrator account to its Keycloak user: a single read by id,
 * {@code f:<component>:<accountId>}. No attribute search and no conflict check, because without
 * user mirrors nothing can carry the same account twice.
 */
public final class AccountUsers {

    public static final String ACCOUNT_ID_ATTRIBUTE = "orchestratorAccountId";

    private AccountUsers() {
    }

    /** The account's Keycloak user, or {@code null} if there is no such account. */
    public static UserModel findByAccountId(KeycloakSession session, RealmModel realm, String accountId) {
        return OrchestratorStorageProviderFactory.componentIn(realm)
                .map(component -> session.users().getUserById(realm, StorageId.keycloakId(component, accountId)))
                .orElse(null);
    }
}
