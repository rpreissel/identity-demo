package com.example.identity.kcext.login;

import com.example.identity.kcext.client.OrchestratorSettings;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowCallbackFactory;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * A {@link AuthenticationFlowCallbackFactory}, so {@link OrchestratorResumeAuthenticator#onTopFlowSuccess}
 * fires at the end of the top-level flow (end report, docs/05-api.md Abschnitt 3b). This needs
 * the execution wrapped in its own subflow in the realm config.
 */
public class OrchestratorResumeAuthenticatorFactory implements AuthenticationFlowCallbackFactory {

    public static final String PROVIDER_ID = "orchestrator-resume-authenticator";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Orchestrator Resume";
    }

    @Override
    public String getReferenceCategory() {
        return "orchestrator";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[]{
                AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.DISABLED
        };
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "First step of every orchestrator-driven browser flow run (docs/05-api.md "
                + "Abschnitt 3) - creates/resumes this run's channel and, on step-up, names the Keycloak session (ADR-59). Place "
                + "before auth-cookie, at the start of the flow.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new OrchestratorResumeAuthenticator(session, OrchestratorSettings.of(session).newClient());
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
