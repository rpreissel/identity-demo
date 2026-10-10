package com.example.identity.kcext.model;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.RealmModel;
import org.keycloak.storage.UserStorageProvider;

import java.util.Optional;

/** The orchestrator's user-storage component of a realm, where the plugin's settings live. */
public final class OrchestratorComponent {

    public static final String PROVIDER_ID = "orchestrator";

    private OrchestratorComponent() {
    }

    /**
     * This realm's component, found by its providerId, since its id differs per environment. Empty
     * until the migration has created it.
     */
    public static Optional<ComponentModel> in(RealmModel realm) {
        return realm.getStorageProviders(UserStorageProvider.class)
                .filter(component -> PROVIDER_ID.equals(component.getProviderId()))
                .findFirst();
    }
}
