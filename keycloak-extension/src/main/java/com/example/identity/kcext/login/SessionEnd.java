package com.example.identity.kcext.login;

import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;

/**
 * The latest end of a Keycloak user session without further activity, which caps the Web channel's
 * expiry (ADR-43). Idle time counts from the last refresh, the lifespan from the start. Deliberately
 * the regular values: remember-me values are never shorter, so the channel may end early, never late.
 */
final class SessionEnd {

    private SessionEnd() {
    }

    /** {@code existing} is the session this flow run continues, or null if it is about to create one. */
    static long epochSecond(RealmModel realm, UserSessionModel existing, long nowSeconds) {
        return existing == null
                ? epochSecond(realm, nowSeconds, nowSeconds)
                : epochSecond(realm, existing.getStarted(), existing.getLastSessionRefresh());
    }

    static long epochSecond(RealmModel realm, long startedSeconds, long lastRefreshSeconds) {
        long idleEnd = lastRefreshSeconds + realm.getSsoSessionIdleTimeout();
        long lifespanEnd = startedSeconds + realm.getSsoSessionMaxLifespan();
        return Math.min(idleEnd, lifespanEnd);
    }
}
