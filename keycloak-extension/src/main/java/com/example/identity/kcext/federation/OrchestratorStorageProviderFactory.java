package com.example.identity.kcext.federation;

import com.example.identity.kcext.model.OrchestratorComponent;
import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.client.OrchestratorSettings;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.storage.UserStorageProviderFactory;

import java.util.List;

/**
 * Factory fuer {@link OrchestratorStorageProvider} und zugleich die Komponente, an der die
 * Konfiguration der Extension haengt ({@link OrchestratorSettings}). Der {@link OrchestratorClient}
 * entsteht pro Komponenteninstanz, damit eine in der Admin-Console geaenderte URL ohne Neustart ankommt.
 */
public class OrchestratorStorageProviderFactory implements UserStorageProviderFactory<OrchestratorStorageProvider> {

    public static final String PROVIDER_ID = OrchestratorComponent.PROVIDER_ID;


    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Traegt die Konfiguration der Orchestrator-Extension und delegiert Passwort-Pruefung/-Aenderung "
                + "an den eigenen auth_password-Speicher des Orchestrators - kein Passwort liegt je in Keycloak, "
                + "gleiches Prinzip wie ein LDAP-Federation-Provider, der an sein Verzeichnis delegiert.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return OrchestratorSettings.CONFIG_PROPERTIES;
    }

    @Override
    public OrchestratorStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new OrchestratorStorageProvider(session, model, () -> OrchestratorSettings.from(model).newClient());
    }

    /**
     * Keycloak ruft das beim Anlegen und Aendern der Komponente auf und speichert das Modell danach
     * (siehe {@link OrchestratorSettings#ensureSigningKey}).
     */
    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model) {
        OrchestratorSettings.ensureSigningKey(model);
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
