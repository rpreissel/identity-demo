package com.example.identity.kcext.login;

import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The latest session end that caps a Web channel's expiry (ADR-43). */
class SessionEndTest {

    private static final int IDLE = 1800;
    private static final int LIFESPAN = 36000;

    /** Only the regular session limits are needed; reading remember-me limits is a test error. */
    private static RealmModel realm() {
        return (RealmModel) Proxy.newProxyInstance(RealmModel.class.getClassLoader(), new Class<?>[]{RealmModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSsoSessionIdleTimeout" -> IDLE;
                    case "getSsoSessionMaxLifespan" -> LIFESPAN;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aSessionCreatedByThisFlowRunEndsAfterTheIdleTimeout() {
        assertEquals(1_000 + IDLE, SessionEnd.epochSecond(realm(), null, 1_000));
    }

    @Test
    void anExistingSessionEndsAtItsIdleTimeoutCountedFromTheLastRefresh() {
        assertEquals(5_000 + IDLE, SessionEnd.epochSecond(realm(), 1_000, 5_000));
    }

    @Test
    void anExistingSessionNearItsLifespanEndsAtTheLifespan() {
        long started = 1_000;
        long lastRefresh = started + LIFESPAN - 60;
        assertEquals(started + LIFESPAN, SessionEnd.epochSecond(realm(), started, lastRefresh));
    }

    @Test
    void anExistingSessionUsesItsOwnStartAndLastRefreshInsteadOfTheCurrentTime() {
        UserSessionModel existing = (UserSessionModel) Proxy.newProxyInstance(
                UserSessionModel.class.getClassLoader(), new Class<?>[]{UserSessionModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStarted" -> 1_000;
                    case "getLastSessionRefresh" -> 5_000;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        assertEquals(5_000 + IDLE, SessionEnd.epochSecond(realm(), existing, 6_000));
    }

    @Test
    void sessionTimestampsBeyondTheIntegerRangeDoNotOverflow() {
        long started = Integer.MAX_VALUE;
        assertEquals(started + IDLE, SessionEnd.epochSecond(realm(), null, started));
    }
}
