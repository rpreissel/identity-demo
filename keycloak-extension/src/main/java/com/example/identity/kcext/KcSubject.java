package com.example.identity.kcext;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Whom a channel is signed in as: an orchestrator account, or an invitation after a one-time
 * password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Each lives in a federation of its own, so
 * the two never share a Keycloak user or a {@code sub}.
 */
record KcSubject(Kind kind, String id) {

    enum Kind { ACCOUNT, INVITATION }

    static KcSubject account(long accountId) {
        return new KcSubject(Kind.ACCOUNT, String.valueOf(accountId));
    }

    static KcSubject invitation(String invitation) {
        return new KcSubject(Kind.INVITATION, invitation);
    }

    /** The subject a Keycloak user stands for, or {@code null} for none of ours. */
    static KcSubject of(UserModel user) {
        if (user == null) return null;
        Long accountId = OrchestratorNotes.accountId(user);
        if (accountId != null) return account(accountId);
        String invitation = user.getFirstAttribute(InvitationUsers.INVITATION_ATTRIBUTE);
        return invitation == null || invitation.isBlank() ? null : invitation(invitation);
    }

    /** This subject's Keycloak user, or {@code null} if its federation does not know it. */
    UserModel findUser(KeycloakSession session, RealmModel realm) {
        return switch (kind) {
            case ACCOUNT -> AccountUsers.findByAccountId(session, realm, id);
            case INVITATION -> InvitationUsers.findByInvitation(session, realm, id);
        };
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase() + " " + id;
    }
}
