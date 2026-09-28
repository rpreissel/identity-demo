package com.example.identity.kcext;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The QR waiting page's status check (docs/adr/ADR-045-qr-warteseite-fragt-im-hintergrund.md):
 * which session it answers for, what it answers, and that it only reads.
 */
class QrWaitStatusResourceProviderTest {

    private static final String CHANNEL = "0b9d7c1e-0000-4000-8000-000000000001";
    private static final String TOOL_SESSION = "0b9d7c1e-0000-4000-8000-000000000002";

    /** Records every call that would change the authentication session. */
    private final List<String> writes = new ArrayList<>();

    private AuthenticationSessionModel authSession(Map<String, String> notes) {
        return (AuthenticationSessionModel) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{AuthenticationSessionModel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAuthNote")) return notes.get((String) args[0]);
                    if (method.getName().startsWith("set") || method.getName().startsWith("remove")
                            || method.getName().startsWith("clear") || method.getName().startsWith("add")) {
                        writes.add(method.getName());
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private AuthenticationSessionModel waitingOn(String toolId) {
        Map<String, String> notes = new HashMap<>();
        notes.put(OrchestratorNotes.CHANNEL_SESSION_ID, CHANNEL);
        notes.put(OrchestratorNotes.PENDING_KIND, "tool");
        notes.put(OrchestratorNotes.PENDING_TOOL_ID, toolId);
        notes.put(OrchestratorNotes.PENDING_TOOL_SESSION_ID, TOOL_SESSION);
        return authSession(notes);
    }

    private static OrchestratorClient.Next tool(String toolId, String step, String toolSessionId) {
        return new OrchestratorClient.Next("tool", toolId, null, step, toolSessionId);
    }

    @Test
    void withoutAnAuthenticationSessionThereIsNoAnswer() {
        var answer = QrWaitStatusResourceProvider.answer(null, (c, s, t) -> {
            throw new AssertionError("no session, no orchestrator call");
        });
        assertEquals(404, answer.status());
        assertNull(answer.body());
    }

    /** A realm that knows exactly one client, {@code identity-demo-web}. */
    private RealmModel realmWith(ClientModel client) {
        return (RealmModel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RealmModel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getClientByClientId")) return "identity-demo-web".equals(args[0]) ? client : null;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private ClientModel client() {
        return (ClientModel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ClientModel.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void theSessionIsTheOneTheCookieNamesForThisClientAndTab() {
        ClientModel client = client();
        AuthenticationSessionModel found = waitingOn("auth-qr");
        List<Object> asked = new ArrayList<>();
        AuthenticationSessionModel result = QrWaitStatusResourceProvider.currentAuthSession(realmWith(client), "identity-demo-web", "tab-1",
                (c, tab) -> {
                    asked.add(c);
                    asked.add(tab);
                    return found;
                });
        assertSame(found, result);
        assertEquals(List.of(client, "tab-1"), asked);
    }

    @Test
    void withoutClientOrTabOrWithAnUnknownClientThereIsNoSession() {
        BiFunction<ClientModel, String, AuthenticationSessionModel> never = (c, tab) -> {
            throw new AssertionError("no cookie lookup");
        };
        // A null realm: the guard must answer before touching it.
        assertNull(QrWaitStatusResourceProvider.currentAuthSession(null, null, "tab", never));
        assertNull(QrWaitStatusResourceProvider.currentAuthSession(null, "identity-demo-web", " ", never));
        assertNull(QrWaitStatusResourceProvider.currentAuthSession(realmWith(client()), "someone-else", "tab", never));
    }

    @Test
    void waitingWhileTheOrchestratorStillNamesThisToolSessionInItsWaitingStep() {
        List<String> reads = new ArrayList<>();
        var answer = QrWaitStatusResourceProvider.answer(waitingOn("auth-qr-lookup"), (channel, toolSession, toolId) -> {
            reads.add(channel + " " + toolSession + " " + toolId);
            return tool("auth-qr-lookup", "waitForApp", TOOL_SESSION);
        });
        assertEquals(200, answer.status());
        assertEquals("{\"state\":\"waiting\"}", answer.body());
        assertEquals(List.of(CHANNEL + " " + TOOL_SESSION + " auth-qr-lookup"), reads);
    }

    @Test
    void readyOnceTheAppApprovedOrTheRequestIsClosed() {
        assertEquals(QrWaitStatusResourceProvider.State.READY,
                QrWaitStatusResourceProvider.state(waitingOn("auth-qr"), (c, s, t) -> tool("auth-qr", "enterCode", TOOL_SESSION)));
        assertEquals(QrWaitStatusResourceProvider.State.READY,
                QrWaitStatusResourceProvider.state(waitingOn("auth-qr"), (c, s, t) -> tool("auth-qr", "closed", TOOL_SESSION)));
    }

    @Test
    void readyWhenTheJourneyMovedOnToSomethingElse() {
        assertEquals(QrWaitStatusResourceProvider.State.READY, QrWaitStatusResourceProvider.state(waitingOn("auth-qr"),
                (c, s, t) -> new OrchestratorClient.Next("orchestrator", null, "auth", "selectMethod", null)));
        assertEquals(QrWaitStatusResourceProvider.State.READY, QrWaitStatusResourceProvider.state(waitingOn("auth-qr"),
                (c, s, t) -> tool("auth-qr", "waitForApp", "another-tool-session")));
    }

    @Test
    void readyWhenTheReadFailsSoThatTheFormPostReportsWhy() {
        assertEquals(QrWaitStatusResourceProvider.State.READY, QrWaitStatusResourceProvider.state(waitingOn("auth-qr"),
                (c, s, t) -> {
                    throw new java.io.IOException("orchestrator down");
                }));
    }

    @Test
    void readyWithoutAskingWhenThePageIsNotAQrWaitingPage() {
        QrWaitStatusResourceProvider.ToolReader never = (c, s, t) -> {
            throw new AssertionError("nothing to ask");
        };
        assertEquals(QrWaitStatusResourceProvider.State.READY, QrWaitStatusResourceProvider.state(waitingOn("auth-sms"), never));
        assertEquals(QrWaitStatusResourceProvider.State.READY, QrWaitStatusResourceProvider.state(authSession(Map.of(
                OrchestratorNotes.CHANNEL_SESSION_ID, CHANNEL, OrchestratorNotes.PENDING_KIND, "select")), never));
    }

    @Test
    void theCheckNeverChangesTheAuthenticationSession() {
        QrWaitStatusResourceProvider.answer(waitingOn("auth-qr"), (c, s, t) -> tool("auth-qr", "waitForApp", TOOL_SESSION));
        QrWaitStatusResourceProvider.answer(waitingOn("auth-qr"), (c, s, t) -> tool("auth-qr", "enterCode", TOOL_SESSION));
        assertTrue(writes.isEmpty(), "writes: " + writes);
    }
}
