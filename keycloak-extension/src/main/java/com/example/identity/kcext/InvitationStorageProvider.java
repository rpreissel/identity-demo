package com.example.identity.kcext;

import java.io.IOException;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;

/**
 * The invitations of the orchestrator are Keycloak users of their own, read through and never
 * copied (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Only a lookup by id exists: an invitation
 * is never found by name or address, has no credential and is never listed. So Keycloak's own
 * forms can never sign one in; only the orchestrator's authenticator names it.
 */
public class InvitationStorageProvider implements UserStorageProvider, UserLookupProvider {

    private final KeycloakSession session;
    private final ComponentModel model;
    private final OrchestratorClient client;

    InvitationStorageProvider(KeycloakSession session, ComponentModel model, OrchestratorClient client) {
        this.session = session;
        this.model = model;
        this.client = client;
    }

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        String invitation = StorageId.externalId(id);
        try {
            KcInvitation found = client.invitationById(invitation);
            return found == null ? null : new InvitationUser(session, realm, model, found);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Unreachable is an error, never "unknown user" (ADR-38).
            throw new ModelException("Orchestrator invitation lookup failed", e);
        }
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        return null;
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        return null;
    }

    @Override
    public void close() {
    }
}
