package com.example.identity.kcext.federation;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.login.OrchestratorNotes;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryMethodsProvider;
import org.keycloak.storage.user.UserRegistrationProvider;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The orchestrator's accounts are Keycloak's users, read through on demand and never copied (ADR-38).
 * Every lookup asks the orchestrator and wraps the answer as an {@link OrchestratorUser}; Keycloak
 * caches it briefly. Keycloak checks and stores no credentials: every sign-in step is an orchestrator
 * tool (ADR-58). As a {@link CredentialInputUpdater} it refuses every credential, so neither an admin
 * nor a required action can leave one in Keycloak's own store.
 * Searches return at most one user by exact username, email or account id: nobody pages through
 * millions of users, and no login needs to.
 */
public class OrchestratorStorageProvider implements UserStorageProvider, UserRegistrationProvider,
        UserLookupProvider, UserQueryMethodsProvider, CredentialInputUpdater {


    private final KeycloakSession session;
    private final ComponentModel model;
    private final Supplier<OrchestratorClient> clientSource;
    private OrchestratorClient client;

    /**
     * The client is built on first use, not here: Keycloak also instantiates this provider when it
     * removes the realm, and an unreadable component config must not block that (DPoP-demo-egyu).
     */
    OrchestratorStorageProvider(KeycloakSession session, ComponentModel model, Supplier<OrchestratorClient> clientSource) {
        this.session = session;
        this.model = model;
        this.clientSource = clientSource;
    }

    private OrchestratorClient client() {
        if (client == null) client = clientSource.get();
        return client;
    }

    // Lookup ---------------------------------------------------------------------------------

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        String external = StorageId.externalId(id);
        long accountId;
        try {
            accountId = Long.parseLong(external);
        } catch (NumberFormatException e) {
            return null;
        }
        return wrap(realm, read(() -> client().accountById(accountId)));
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        return wrap(realm, read(() -> client().accountByUsername(username)));
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        return wrap(realm, read(() -> client().accountByEmail(email)));
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> params, Integer firstResult, Integer maxResults) {
        if (firstResult != null && firstResult > 0) return Stream.empty();
        String term = firstNonBlank(params.get(UserModel.SEARCH), params.get(UserModel.USERNAME), params.get(UserModel.EMAIL));
        if (term == null || term.contains("*")) return Stream.empty();
        UserModel byUsername = getUserByUsername(realm, term.trim());
        return byUsername != null ? Stream.of(byUsername) : Stream.ofNullable(getUserByEmail(realm, term.trim()));
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm, String attrName, String attrValue) {
        if (!OrchestratorNotes.USER_ATTR_ACCOUNT_ID.equals(attrName)) return Stream.empty();
        return Stream.ofNullable(getUserById(realm, StorageId.keycloakId(model, attrValue)));
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group, Integer firstResult, Integer maxResults) {
        return Stream.empty();
    }

    private UserModel wrap(RealmModel realm, KcAccount account) {
        return account == null ? null : new OrchestratorUser(session, realm, model, account);
    }

    /**
     * An unreachable orchestrator is an error, not "no such user": answering {@code null} would let
     * Keycloak treat a known user as unknown (user_not_found, a failed login counted against no one).
     */
    private static KcAccount read(Lookup lookup) {
        try {
            return lookup.run();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ModelException("Orchestrator account lookup failed", e);
        }
    }

    @FunctionalInterface
    private interface Lookup {
        KcAccount run() throws IOException, InterruptedException;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    // Registration and removal ----------------------------------------------------------------

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        // Users remain local Keycloak users; this provider only participates in their removal.
        return null;
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        // UserStorageManager delegates to local storage after this provider approves removal.
        return true;
    }

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return true;
    }

    /**
     * Throws rather than returning false: false would let Keycloak's built-in provider store the
     * credential locally, next to the orchestrator's methods.
     */
    @Override
    public boolean updateCredential(RealmModel realm, UserModel user, CredentialInput input) {
        throw new ReadOnlyException("Credentials are managed by the orchestrator, not in Keycloak");
    }

    @Override
    public void disableCredentialType(RealmModel realm, UserModel user, String credentialType) {
    }

    @Override
    public Stream<String> getDisableableCredentialTypesStream(RealmModel realm, UserModel user) {
        return Stream.empty();
    }

    @Override
    public void close() {
    }
}
