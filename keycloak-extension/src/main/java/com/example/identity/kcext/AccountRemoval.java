package com.example.identity.kcext;

import jakarta.ws.rs.DELETE;
import org.keycloak.models.session.UserSessionPersisterProvider;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;
import org.keycloak.storage.UserStorageUtil;

import java.util.Map;

/**
 * {@code DELETE /admin/realms/{realm}/orchestrator-accounts/{accountId}}: the orchestrator deleted an
 * account, and Keycloak drops what it keeps locally for that user (sessions, login failures, cache,
 * federated storage). Not Keycloak's {@code DELETE users/{id}}: that looks the user up through the
 * federation first and answers 404 for a deleted account. Callers need {@code manage-users}.
 */
public final class AccountRemoval {

    private AccountRemoval() {
    }

    public static final class Resource {
        private final KeycloakSession session;
        private final RealmModel realm;
        private final AdminPermissionEvaluator auth;

        Resource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth) {
            this.session = session;
            this.realm = realm;
            this.auth = auth;
        }

        @DELETE
        @Path("{accountId}")
        public Response remove(@PathParam("accountId") long accountId) {
            auth.users().requireManage();
            ComponentModel component = OrchestratorStorageProviderFactory.componentIn(realm)
                    .orElseThrow(() -> new IllegalStateException("No orchestrator user federation in realm " + realm.getName()));
            // The user as far as its id goes - there is no account left to read anything else from.
            OrchestratorUser gone = new OrchestratorUser(session, realm, component,
                    new KcAccount(accountId, "account-" + accountId, null, false, null, null, Map.of()));
            session.sessions().removeUserSessions(realm, gone);
            // Keycloak 26 keeps user sessions in the database too. Its own user deletion clears them
            // through the persister; removeUserSessions alone leaves them, since it cannot load the
            // deleted user they belong to.
            UserSessionPersisterProvider persister = session.getProvider(UserSessionPersisterProvider.class);
            if (persister != null) persister.onUserRemoved(realm, gone);
            session.loginFailures().removeUserLoginFailure(realm, gone.getId());
            UserStorageUtil.userFederatedStorage(session).preRemove(realm, gone);
            var cache = UserStorageUtil.userCache(session);
            if (cache != null) cache.evict(realm, gone);
            return Response.noContent().build();
        }
    }

    public static final class Provider implements AdminRealmResourceProvider {
        @Override
        public Object getResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
            return new Resource(session, realm, auth);
        }

        @Override
        public void close() {
        }
    }

    /** The id is the URL segment: {@code /admin/realms/{realm}/orchestrator-accounts}. */
    public static final class Factory implements AdminRealmResourceProviderFactory {
        public static final String ID = "orchestrator-accounts";

        @Override
        public String getId() {
            return ID;
        }

        @Override
        public AdminRealmResourceProvider create(KeycloakSession session) {
            return new Provider();
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
}
