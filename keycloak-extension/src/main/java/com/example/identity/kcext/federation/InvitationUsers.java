package com.example.identity.kcext;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.StorageId;

/** Resolves an invitation to its Keycloak user: a single read by id, {@code f:<component>:<invitation>}. */
public final class InvitationUsers {

    /** Mapped to the token claim {@code invitation}. */
    public static final String INVITATION_ATTRIBUTE = "orchestratorInvitation";

    private InvitationUsers() {
    }

    /** The invitation's Keycloak user, or {@code null} if there is no such invitation. */
    public static UserModel findByInvitation(KeycloakSession session, RealmModel realm, String invitation) {
        return InvitationStorageProviderFactory.componentIn(realm)
                .map(component -> session.users().getUserById(realm, StorageId.keycloakId(component, invitation)))
                .orElse(null);
    }
}
