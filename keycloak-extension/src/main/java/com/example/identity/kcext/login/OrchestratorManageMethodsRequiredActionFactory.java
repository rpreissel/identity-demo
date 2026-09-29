package com.example.identity.kcext.login;

import com.example.identity.kcext.client.OrchestratorSettings;
import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Eine Instanz pro Session: der Client haengt an der Realm-Konfiguration ({@link OrchestratorSettings}),
 * die erst mit der Session feststeht. Die Realm-Registrierung ({@code defaultAction=false}) macht
 * eine Migration.
 */
public class OrchestratorManageMethodsRequiredActionFactory implements RequiredActionFactory {

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return new OrchestratorManageMethodsRequiredAction(OrchestratorSettings.of(session).newClient());
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

    @Override
    public String getId() {
        return OrchestratorManageMethodsRequiredAction.PROVIDER_ID;
    }

    @Override
    public String getDisplayText() {
        return "Anmeldeverfahren verwalten (Orchestrator)";
    }
}
