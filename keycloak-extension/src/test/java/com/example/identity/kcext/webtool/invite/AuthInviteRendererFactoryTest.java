package com.example.identity.kcext.webtool.invite;

import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.forms.login.LoginFormsProvider;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * auth-invite has one step "auth" (ADR-48). Its page hands the demo's open invitations to the
 * picker, and says "null" when the demo discloses none.
 */
class AuthInviteRendererFactoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A form that records what the renderer sets and which template it asks for. */
    private static LoginFormsProvider recording(Map<String, Object> seen) {
        return (LoginFormsProvider) Proxy.newProxyInstance(LoginFormsProvider.class.getClassLoader(),
                new Class<?>[]{LoginFormsProvider.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setAttribute" -> seen.put((String) args[0], args[1]);
                        case "createForm" -> seen.put("template", args[0]);
                        default -> { }
                    }
                    return method.getReturnType().isInstance(proxy) ? proxy : null;
                });
    }

    private static WebToolRenderContext context(String step, Map<String, JsonNode> demo) {
        return new WebToolRenderContext("auth-invite", step, Map.of(), demo, null, null, Set.of(), null);
    }

    @Test
    void theAuthStepShowsThePageWithTheOpenInvitations() {
        JsonNode invitations = JSON.valueToTree(List.of(Map.of("label", "Max – Bonusprogramm", "kvnr", "A123456789", "code", "ABCD-EFGH-JKLM")));
        Map<String, Object> seen = new HashMap<>();

        new AuthInviteRendererFactory().render(recording(seen), context("auth", Map.of("invitations", invitations)));

        assertEquals("tool-auth-invite.ftl", seen.get("template"));
        assertEquals(invitations.toString(), seen.get("demoInvitationsJson"));
    }

    @Test
    void withoutDisclosedInvitationsThePickerGetsNull() {
        Map<String, Object> seen = new HashMap<>();

        new AuthInviteRendererFactory().render(recording(seen), context("auth", Map.of()));

        assertEquals("null", seen.get("demoInvitationsJson"));
    }

    @Test
    void anUnknownStepIsLeftToTheGenericPage() {
        Map<String, Object> seen = new HashMap<>();

        assertNull(new AuthInviteRendererFactory().render(recording(seen), context("other", Map.of())));
        assertTrue(seen.isEmpty());
    }
}
