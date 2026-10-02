package com.example.identity.kcext.grant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.common.Profile;
import org.keycloak.common.util.Time;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.services.CorsErrorResponseException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Mit {@code session_id} setzt der Grant nur eine noch gültige Sitzung fort, die er selbst für
 * denselben Nutzer geöffnet hat; jede andere lehnt er ab, statt eine neue zu öffnen (I-24, ADR-43).
 * Geprüft wird die Fortsetzung der Sitzung; der Rest von {@code process} braucht einen Keycloak-Server.
 */
class AccountTokenSessionTest {

    private static final int IDLE = 1800;
    private static final int LIFESPAN = 36000;

    private final List<String> sessionCalls = new ArrayList<>();
    private final Map<String, UserSessionModel> sessions = new HashMap<>();
    private final List<String> eventSessions = new ArrayList<>();

    // AuthenticationManager.isSessionValid fragt Features ab.
    @BeforeAll
    static void keycloakFeatures() {
        if (Profile.getInstance() == null) {
            Profile.defaults();
        }
    }

    @Test
    void theCallersOwnValidSessionIsContinued() {
        UserSessionModel own = session("s-1", "user-a", true, activeNow());
        sessions.put("s-1", own);

        assertSame(own, grant().continueSession(user("user-a"), "s-1"));
        assertEquals(List.of("getUserSession"), sessionCalls);
    }

    @Test
    void anotherUsersSessionIsRejected() {
        sessions.put("s-1", session("s-1", "user-b", true, activeNow()));

        assertRejectedWithoutNewSession("s-1");
    }

    // Etwa eine Sitzung des Web-Kanals oder eines anderen Clients: Ihr fehlt die Markierung des Grants.
    @Test
    void aSessionTheGrantDidNotOpenIsRejected() {
        sessions.put("s-1", session("s-1", "user-a", false, activeNow()));

        assertRejectedWithoutNewSession("s-1");
    }

    @Test
    void anExpiredSessionIsNeitherContinuedNorReplaced() {
        int longAgo = Time.currentTime() - IDLE - 3600;
        sessions.put("s-1", session("s-1", "user-a", true, longAgo));

        assertRejectedWithoutNewSession("s-1");
    }

    @Test
    void anUnknownSessionIsNeitherContinuedNorReplaced() {
        assertRejectedWithoutNewSession("gone-4711");
    }

    /** Rejected: the id lands in the event, never in the OAuth error response, and no session is opened. */
    private void assertRejectedWithoutNewSession(String sessionId) {
        CorsErrorResponseException rejection = assertThrows(CorsErrorResponseException.class,
                () -> grant().continueSession(user("user-a"), sessionId));

        assertEquals(List.of(sessionId), eventSessions);
        assertFalse(rejection.getErrorDescription().contains(sessionId));
        assertEquals(List.of("getUserSession"), sessionCalls);
    }

    private AccountTokenGrantType grant() {
        return new Grant(keycloakSession(), realm(), new RecordingEvents(eventSessions));
    }

    /** Sets what the grant's base class otherwise takes from the request context. */
    private static final class Grant extends AccountTokenGrantType {
        Grant(KeycloakSession session, RealmModel realm, EventBuilder event) {
            this.session = session;
            this.realm = realm;
            this.event = event;
        }
    }

    /** Records the session ids the grant names in its event. */
    private static final class RecordingEvents extends EventBuilder {
        private final List<String> sessionIds;

        RecordingEvents(List<String> sessionIds) {
            super(AccountTokenSessionTest.realm(), eventSession());
            this.sessionIds = sessionIds;
        }

        @Override
        public EventBuilder session(String sessionId) {
            sessionIds.add(sessionId);
            return this;
        }

        @Override
        public EventBuilder detail(String key, String value) {
            return this;
        }

        @Override
        public void error(String error) {
        }
    }

    /** What EventBuilder's constructor asks for; with events off and no listeners that is little. */
    private static KeycloakSession eventSession() {
        KeycloakSessionFactory factory = proxy(KeycloakSessionFactory.class, (method, args) -> switch (method) {
            case "getProviderFactoriesStream" -> java.util.stream.Stream.empty();
            default -> throw new UnsupportedOperationException(method);
        });
        return proxy(KeycloakSession.class, (method, args) -> switch (method) {
            case "getKeycloakSessionFactory" -> factory;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private KeycloakSession keycloakSession() {
        UserSessionProvider provider = proxy(UserSessionProvider.class, (method, args) -> {
            sessionCalls.add(method);
            if (method.equals("getUserSession")) return sessions.get((String) args[1]);
            throw new UnsupportedOperationException(method);
        });
        return proxy(KeycloakSession.class, (method, args) -> switch (method) {
            case "sessions" -> provider;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static RealmModel realm() {
        return proxy(RealmModel.class, (method, args) -> switch (method) {
            case "isRememberMe" -> false;
            case "getSsoSessionIdleTimeout" -> IDLE;
            case "getSsoSessionMaxLifespan" -> LIFESPAN;
            case "getSsoSessionIdleTimeoutRememberMe", "getSsoSessionMaxLifespanRememberMe" -> 0;
            // Keycloak liest die Offline-Frist auch für eine Online-Sitzung, verwendet sie dann aber nicht.
            case "getOfflineSessionIdleTimeout", "getOfflineSessionMaxLifespan" -> 0;
            case "isOfflineSessionMaxLifespanEnabled" -> false;
            // The recording EventBuilder stores nothing, so the realm keeps events off.
            case "isEventsEnabled" -> false;
            case "getId", "getName" -> "realm";
            case "getEventsListenersStream" -> java.util.stream.Stream.empty();
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static UserModel user(String id) {
        return proxy(UserModel.class, (method, args) -> switch (method) {
            case "getId" -> id;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static int activeNow() {
        return Time.currentTime() - 60;
    }

    private static UserSessionModel session(String id, String userId, boolean openedByGrant, int lastRefresh) {
        UserModel owner = user(userId);
        return proxy(UserSessionModel.class, (method, args) -> switch (method) {
            case "getId" -> id;
            case "getUser" -> owner;
            case "getNote" -> AccountTokenGrantType.SESSION_MARKER_NOTE.equals(args[0]) && openedByGrant ? "true" : null;
            case "isRememberMe", "isOffline" -> false;
            case "getStarted" -> lastRefresh;
            case "getLastSessionRefresh" -> lastRefresh;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private interface Answer {
        Object answer(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> answer.answer(method.getName(), args);
                });
    }
}
