package com.example.identity.kcext.model;

/**
 * Whom a channel is signed in as: an orchestrator account, or an invitation after a one-time
 * password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Each lives in a federation of its own, so
 * the two never share a Keycloak user or a {@code sub}. {@code federation.Subjects} maps it to and
 * from Keycloak's users.
 */
public record KcSubject(Kind kind, String id) {

    public enum Kind { ACCOUNT, INVITATION }

    public static KcSubject account(long accountId) {
        return new KcSubject(Kind.ACCOUNT, String.valueOf(accountId));
    }

    public static KcSubject invitation(String invitation) {
        return new KcSubject(Kind.INVITATION, invitation);
    }



    @Override
    public String toString() {
        return kind.name().toLowerCase() + " " + id;
    }
}
