package com.example.identity.kcext;

import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The latest session end that caps a Web channel's expiry (ADR-43). */
class SessionEndTest {

    private static final int IDLE = 1800;
    private static final int LIFESPAN = 36000;

    /** Only the four values the calculation reads; any other call is a test error. */
    private static RealmModel realm() {
        return (RealmModel) Proxy.newProxyInstance(RealmModel.class.getClassLoader(), new Class<?>[]{RealmModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSsoSessionIdleTimeout" -> IDLE;
                    case "getSsoSessionMaxLifespan" -> LIFESPAN;
                    case "getSsoSessionIdleTimeoutRememberMe", "getSsoSessionMaxLifespanRememberMe" -> 0;
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
}
