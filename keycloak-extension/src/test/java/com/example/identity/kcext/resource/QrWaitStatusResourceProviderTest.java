package com.example.identity.kcext.resource;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.login.OrchestratorNotes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

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

    @Test
    void withoutAnAuthenticationSessionThereIsNoAnswer() {
        var answer = QrWaitStatusResourceProvider.answer(null, (c, s, t) -> {
            throw new AssertionError("no session, no orchestrator call");
        });
        assertEquals(404, answer.status());
        assertNull(answer.body());
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

    @ParameterizedTest
    @ValueSource(strings = {"enterCode", "closed"})
    void readyOnceTheAppApprovedOrTheRequestIsClosed(String step) {
        assertEquals(QrWaitStatusResourceProvider.State.READY,
                QrWaitStatusResourceProvider.state(waitingOn("auth-qr"), (c, s, t) -> tool("auth-qr", step, TOOL_SESSION)));
    }

    static Stream<OrchestratorClient.Next> somethingElse() {
        return Stream.of(
                new OrchestratorClient.Next("orchestrator", null, "auth", "selectMethod", null),
                tool("auth-qr", "waitForApp", "another-tool-session"));
    }

    @ParameterizedTest
    @MethodSource("somethingElse")
    void readyWhenTheJourneyMovedOnToSomethingElse(OrchestratorClient.Next next) {
        assertEquals(QrWaitStatusResourceProvider.State.READY,
                QrWaitStatusResourceProvider.state(waitingOn("auth-qr"), (c, s, t) -> next));
    }

    @Test
    void readyWhenTheReadFailsSoThatTheFormPostReportsWhy() {
        assertEquals(QrWaitStatusResourceProvider.State.READY, QrWaitStatusResourceProvider.state(waitingOn("auth-qr"),
                (c, s, t) -> {
                    throw new java.io.IOException("orchestrator down");
                }));
    }

    static Stream<Map<String, String>> notAQrWaitingPage() {
        return Stream.of(
                waitingNotes("auth-sms"),
                Map.of(OrchestratorNotes.CHANNEL_SESSION_ID, CHANNEL, OrchestratorNotes.PENDING_KIND, "select"));
    }

    @ParameterizedTest
    @MethodSource("notAQrWaitingPage")
    void readyWithoutAskingWhenThePageIsNotAQrWaitingPage(Map<String, String> notes) {
        QrWaitStatusResourceProvider.ToolReader never = (c, s, t) -> {
            throw new AssertionError("nothing to ask");
        };
        assertEquals(QrWaitStatusResourceProvider.State.READY, QrWaitStatusResourceProvider.state(authSession(notes), never));
    }

    @Test
    void theCheckNeverChangesTheAuthenticationSession() {
        QrWaitStatusResourceProvider.answer(waitingOn("auth-qr"), (c, s, t) -> tool("auth-qr", "waitForApp", TOOL_SESSION));
        QrWaitStatusResourceProvider.answer(waitingOn("auth-qr"), (c, s, t) -> tool("auth-qr", "enterCode", TOOL_SESSION));
        assertTrue(writes.isEmpty(), "writes: " + writes);
    }

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

    private static Map<String, String> waitingNotes(String toolId) {
        Map<String, String> notes = new HashMap<>();
        notes.put(OrchestratorNotes.CHANNEL_SESSION_ID, CHANNEL);
        notes.put(OrchestratorNotes.PENDING_KIND, "tool");
        notes.put(OrchestratorNotes.PENDING_TOOL_ID, toolId);
        notes.put(OrchestratorNotes.PENDING_TOOL_SESSION_ID, TOOL_SESSION);
        return notes;
    }

    private AuthenticationSessionModel waitingOn(String toolId) {
        return authSession(waitingNotes(toolId));
    }

    private static OrchestratorClient.Next tool(String toolId, String step, String toolSessionId) {
        return new OrchestratorClient.Next("tool", toolId, null, step, toolSessionId);
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
}
