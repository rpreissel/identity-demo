package com.example.identity.kcext.webtool.password;

import com.example.identity.kcext.webtool.WebToolRenderContext;
import org.junit.jupiter.api.Test;
import org.keycloak.forms.login.LoginFormsProvider;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The password lookup page takes over what Keycloak's own form did with login_hint (ADR-58). */
class PasswordLookupRendererFactoryTest {

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

    @Test
    void theClientsLoginHintFillsTheEmailField() {
        Map<String, Object> seen = new HashMap<>();
        var ctx = new WebToolRenderContext("auth-password-lookup", "auth", Map.of(), Map.of(), null, null, Set.of(), null, "max@example.com");

        new PasswordLookupRendererFactory().render(recording(seen), ctx);

        assertEquals("tool-password-lookup.ftl", seen.get("template"));
        assertEquals("max@example.com", seen.get("loginHint"));
    }
}
