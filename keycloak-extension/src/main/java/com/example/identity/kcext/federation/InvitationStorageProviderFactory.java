package com.example.identity.kcext.federation;

import com.example.identity.kcext.client.OrchestratorSettings;

import java.util.Optional;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderFactory;

/**
 * Factory of the invitation federation. It has no configuration of its own: it reaches the
 * orchestrator with the settings of the account federation ({@link OrchestratorSettings#of}).
 */
public class InvitationStorageProviderFactory implements UserStorageProviderFactory<InvitationStorageProvider> {

    public static final String PROVIDER_ID = "orchestrator-invitations";

    public static Optional<ComponentModel> componentIn(RealmModel realm) {
        return realm.getStorageProviders(UserStorageProvider.class)
                .filter(component -> PROVIDER_ID.equals(component.getProviderId()))
                .findFirst();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Liest Einladungen des Orchestrators (Anmeldung mit Einmalkennwort) als eigene Nutzer - "
                + "nur per Id, ohne Suche, ohne Credential.";
    }

    @Override
    public InvitationStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new InvitationStorageProvider(session, model, () -> OrchestratorSettings.of(session).newClient());
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
