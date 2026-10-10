package com.example.identity.kcext.federation;

import com.example.identity.kcext.model.KcSubject;

import com.example.identity.kcext.model.KcInvitation;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.adapter.AbstractUserAdapterFederatedStorage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A Keycloak user that is an invitation, read through (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
 * It carries the person attributes of an account of that person, so its tokens look the same, plus
 * the two markers {@code orchestratorInvitation} and {@code orchestratorProcess}. Everything is
 * read-only; it has no email and no credential. It is enabled only while the invitation is open,
 * so Keycloak refuses every further token once the business system ends it.
 */
final class InvitationUser extends AbstractUserAdapterFederatedStorage {

    /**
     * Includes the account attribute, always empty: kept in Keycloak's own storage, an entered
     * account id would make the invitation look like that account ({@link KcSubject#of}).
     */
    private static final Set<String> OWNED = Set.of(
            UserModel.USERNAME, UserModel.EMAIL, UserModel.FIRST_NAME, UserModel.LAST_NAME, UserModel.EMAIL_VERIFIED, UserModel.ENABLED,
            AccountUsers.ACCOUNT_ID_ATTRIBUTE);

    private final KcInvitation invitation;

    InvitationUser(KeycloakSession session, RealmModel realm, ComponentModel storageProviderModel, KcInvitation invitation) {
        super(session, realm, storageProviderModel);
        this.invitation = invitation;
    }

    @Override
    public String getId() {
        return StorageId.keycloakId(storageProviderModel, invitation.invitation());
    }

    @Override
    public String getUsername() {
        return invitation.username();
    }

    @Override
    public void setUsername(String username) {
        throw readOnly(UserModel.USERNAME);
    }

    @Override
    public boolean isEnabled() {
        return invitation.enabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        throw readOnly(UserModel.ENABLED);
    }

    @Override
    public boolean isEmailVerified() {
        return false;
    }

    @Override
    public void setEmailVerified(boolean verified) {
        throw readOnly(UserModel.EMAIL_VERIFIED);
    }

    @Override
    public String getFirstAttribute(String name) {
        List<String> values = ownValues(name);
        return values != null ? (values.isEmpty() ? null : values.get(0)) : super.getFirstAttribute(name);
    }

    @Override
    public Stream<String> getAttributeStream(String name) {
        List<String> values = ownValues(name);
        return values != null ? values.stream() : super.getAttributeStream(name);
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        Map<String, List<String>> all = new HashMap<>(super.getAttributes());
        OWNED.forEach(name -> all.put(name, ownValues(name)));
        invitation.attributes().forEach((name, value) -> all.put(name, List.of(value)));
        return all;
    }

    @Override
    public void setSingleAttribute(String name, String value) {
        if (isOwned(name)) throw readOnly(name);
        super.setSingleAttribute(name, value);
    }

    @Override
    public void setAttribute(String name, List<String> values) {
        if (isOwned(name)) throw readOnly(name);
        super.setAttribute(name, values);
    }

    @Override
    public void removeAttribute(String name) {
        if (isOwned(name)) throw readOnly(name);
        super.removeAttribute(name);
    }

    /** The invitation's values for [name], or {@code null} when it does not own it (then Keycloak's own storage answers). */
    private List<String> ownValues(String name) {
        return switch (name) {
            case UserModel.USERNAME -> List.of(invitation.username());
            case UserModel.EMAIL -> List.of();
            case UserModel.EMAIL_VERIFIED -> List.of("false");
            case UserModel.ENABLED -> List.of(String.valueOf(invitation.enabled()));
            case UserModel.FIRST_NAME -> invitation.firstName() == null ? List.of() : List.of(invitation.firstName());
            case UserModel.LAST_NAME -> invitation.lastName() == null ? List.of() : List.of(invitation.lastName());
            case AccountUsers.ACCOUNT_ID_ATTRIBUTE -> List.of();
            default -> invitation.attributes().containsKey(name) ? invitation.attribute(name) : null;
        };
    }

    private boolean isOwned(String name) {
        return OWNED.contains(name) || invitation.attributes().containsKey(name);
    }

    private static ReadOnlyException readOnly(String name) {
        return new ReadOnlyException("'" + name + "' belongs to the invitation and is read-only in Keycloak");
    }
}
