package com.example.identity.kcext.bootstrap;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.managers.RealmManager;

import java.net.URI;
import java.util.Locale;

/**
 * Legt im Master-Realm den Client {@code orchestrator-migration} an, mit dem der Orchestrator seine
 * Keycloak-Migrationen ausfuehrt: angemeldet per {@code private_key_jwt} gegen das JWKS des
 * Orchestrators, mit der Master-Rolle {@code create-realm} statt {@code admin} (ADR-25). Laeuft beim
 * Keycloak-Start nach dessen Datenbank-Migration ({@link PostMigrationEvent}) und ist idempotent.
 * Die {@code jwks.url} kommt aus der SPI-Konfiguration, weil es beim Start noch kein Realm gibt.
 */
public class MigrationClientBootstrapFactory implements OrchestratorBootstrapFactory {

    public static final String ID = "migration-client";
    public static final String CLIENT_ID = "orchestrator-migration";
    static final String JWKS_URL = "jwks-url";
    static final String ALLOW_HTTP = "jwks-url-allow-http";

    private static final Logger log = Logger.getLogger(MigrationClientBootstrapFactory.class);

    private String jwksUrl;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public OrchestratorBootstrap create(KeycloakSession session) {
        return () -> {
        };
    }

    @Override
    public void init(Config.Scope config) {
        jwksUrl = config.get(JWKS_URL);
        // Ohne diesen Wert kann sich die Migration nicht anmelden; lieber startet Keycloak gar nicht.
        if (jwksUrl == null || jwksUrl.isBlank()) {
            throw new IllegalStateException("SPI-Option spi-orchestrator-bootstrap--" + ID + "--" + JWKS_URL
                    + " fehlt - ohne sie kann sich die Migration des Orchestrators nicht anmelden");
        }
        requireTrustedJwksUrl(jwksUrl, config.getBoolean(ALLOW_HTTP, false));
    }

    /**
     * Wer unter der jwks.url antwortet, meldet sich als Client mit {@code create-realm} an. Ueber http
     * koennte das jeder auf dem Weg; erlaubt ist es nur im selben Rechner oder Pod (Loopback) oder
     * mit ausdruecklicher Option fuer die lokale Entwicklung ({@code jwks-url-allow-http}).
     */
    static void requireTrustedJwksUrl(String url, boolean allowHttp) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) return;
        if (scheme.equals("http") && (isLoopback(uri.getHost()) || allowHttp)) {
            if (allowHttp && !isLoopback(uri.getHost())) log.warnf("jwks-url %s ohne TLS (%s=true) - nur fuer die lokale Entwicklung", url, ALLOW_HTTP);
            return;
        }
        throw new IllegalStateException("SPI-Option " + JWKS_URL + " muss https sein (http nur fuer Loopback oder mit " + ALLOW_HTTP + "=true): " + url);
    }

    private static boolean isLoopback(String host) {
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                KeycloakModelUtils.runJobInTransaction(factory, this::ensureClient);
            }
        });
    }

    void ensureClient(KeycloakSession session) {
        RealmModel master = session.realms().getRealmByName(Config.getAdminRealm());
        // Die Job-Session hat keinen Realm-Kontext; enableServiceAccount scheitert sonst mit
        // "Session not bound to a realm".
        session.getContext().setRealm(master);
        ClientModel client = master.getClientByClientId(CLIENT_ID);
        if (client == null) {
            client = master.addClient(CLIENT_ID);
            client.setName(CLIENT_ID);
            client.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
            client.setPublicClient(false);
            client.setStandardFlowEnabled(false);
            client.setImplicitFlowEnabled(false);
            client.setDirectAccessGrantsEnabled(false);
            client.setClientAuthenticatorType("client-jwt");
            // Dieselben Attribute wie die Realm-Clients (V1__realm.kc.kts,
            // orchestratorClientJwtAttributes): der Orchestrator signiert mit EC P-256.
            client.setAttribute("use.jwks.url", "true");
            client.setAttribute("token.endpoint.auth.signing.alg", "ES256");
            new ClientManager(new RealmManager(session)).enableServiceAccount(client);
            log.infof("Client '%s' im Master-Realm angelegt (private_key_jwt)", CLIENT_ID);
        }
        // Auch fuer einen vorhandenen Client, damit jeder Start ihn auf diesen Stand bringt.
        client.setFullScopeAllowed(true);
        UserModel serviceAccount = session.users().getServiceAccount(client);
        RoleModel createRealm = master.getRole(AdminRoles.CREATE_REALM);
        if (!serviceAccount.hasDirectRole(createRealm)) {
            serviceAccount.grantRole(createRealm);
            log.infof("Client '%s': Rolle %s zugewiesen", CLIENT_ID, AdminRoles.CREATE_REALM);
        }
        RoleModel admin = master.getRole(AdminRoles.ADMIN);
        if (serviceAccount.hasDirectRole(admin)) {
            grantCreatorRolesOnExistingRealms(session, master, serviceAccount);
            serviceAccount.deleteRoleMapping(admin);
            log.infof("Client '%s': Rolle %s entzogen", CLIENT_ID, AdminRoles.ADMIN);
        }
        if (!jwksUrl.equals(client.getAttribute("jwks.url"))) {
            client.setAttribute("jwks.url", jwksUrl);
            log.infof("Client '%s': jwks.url = %s", CLIENT_ID, jwksUrl);
        }
    }

    /**
     * Traegt die Verwaltungsrollen nach, die Keycloak einem Anleger ohne {@code admin} gibt, fuer
     * Realms, die ein Client mit {@code admin} schon angelegt hatte.
     */
    private void grantCreatorRolesOnExistingRealms(KeycloakSession session, RealmModel master, UserModel serviceAccount) {
        session.realms().getRealmsStream()
                .filter(realm -> !realm.getId().equals(master.getId()))
                .forEach(realm -> {
                    ClientModel realmAdminClient = realm.getMasterAdminClient();
                    for (String roleName : AdminRoles.ALL_REALM_ROLES) {
                        RoleModel role = realmAdminClient.getRole(roleName);
                        if (role != null && !serviceAccount.hasDirectRole(role)) {
                            serviceAccount.grantRole(role);
                        }
                    }
                    log.infof("Client '%s': Verwaltungsrollen fuer Realm '%s' nachgetragen", CLIENT_ID, realm.getName());
                });
    }

    @Override
    public void close() {
    }
}
