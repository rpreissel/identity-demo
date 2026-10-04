package com.example.identity.kcext.token;

import com.example.identity.kcext.login.OrchestratorAuthenticator;
import com.example.identity.kcext.login.OrchestratorNotes;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Copies the orchestrator's acr/amr from the user-session notes into the token (docs/05-api.md
 * Abschnitt 3b). Overwrites Keycloak's own acr instead of merging: on the Web channel the orchestrator
 * alone combines ACR/AMR for the flow run.
 */
public class OrchestratorAcrAmrMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper, OIDCIDTokenMapper {

    public static final String PROVIDER_ID = "orchestrator-acr-amr-mapper";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    /**
     * Must run after Keycloak's own acr/amr mappers, which all have priority 0; otherwise the order
     * depends on mapper ids and Keycloak may replace "loa1" with "1". Higher values run later.
     */
    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public String getDisplayType() {
        return "Orchestrator ACR/AMR";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Writes the orchestrator's combined acr/amr (docs/05-api.md Abschnitt 3b) into "
                + "the token, read from the UserSessionModel notes OrchestratorAuthenticator wrote.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        // The base class only calls setClaim when these two config keys are "true", so they must
        // exist even though this mapper always writes both tokens.
        List<ProviderConfigProperty> configProperties = new ArrayList<>();
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, OrchestratorAcrAmrMapper.class);
        return configProperties;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
                             KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        String acr = userSession.getNote(OrchestratorNotes.USER_SESSION_NOTE_ACR);
        if (acr != null) {
            token.setAcr(acr);
        }
        String amr = userSession.getNote(OrchestratorNotes.USER_SESSION_NOTE_AMR);
        if (amr != null && !amr.isBlank()) {
            List<String> methods = Arrays.asList(amr.split(","));
            token.getOtherClaims().put("amr", methods);
        }
    }
}
