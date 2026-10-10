package com.example.identity.kcext.federation;

import com.example.identity.kcext.model.KcSubject;
import com.example.identity.kcext.model.SessionNotes;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/** Between a {@link KcSubject} and the Keycloak user of its federation. */
public final class Subjects {

    private Subjects() {
    }

    /** The subject a Keycloak user stands for, or {@code null} for none of ours. */
    public static KcSubject of(UserModel user) {
        if (user == null) return null;
        Long accountId = SessionNotes.accountId(user);
        if (accountId != null) return KcSubject.account(accountId);
        String invitation = user.getFirstAttribute(InvitationUsers.INVITATION_ATTRIBUTE);
        return invitation == null || invitation.isBlank() ? null : KcSubject.invitation(invitation);
    }

    /** The subject's Keycloak user, or {@code null} if its federation does not know it. */
    public static UserModel findUser(KcSubject subject, KeycloakSession session, RealmModel realm) {
        return switch (subject.kind()) {
            case ACCOUNT -> AccountUsers.findByAccountId(session, realm, subject.id());
            case INVITATION -> InvitationUsers.findByInvitation(session, realm, subject.id());
        };
    }
}
