package com.example.identity.kcext.model;

import com.example.identity.kcext.api.model.KeycloakAccountView;

import java.util.List;
import java.util.Map;

/**
 * An account as the orchestrator shows it to Keycloak ({@code KeycloakAccountLookupController}) - read
 * through on every lookup, never stored here.
 */
public record KcAccount(
        long accountId,
        String username,
        String email,
        boolean emailVerified,
        String firstName,
        String lastName,
        Map<String, String> attributes
) {
    /** Read through the contract model, so a renamed field breaks the compile, not a login. */
    public static KcAccount from(KeycloakAccountView view) {
        return new KcAccount(
                view.getAccountId(),
                view.getUsername(),
                view.getEmail(),
                Boolean.TRUE.equals(view.getEmailVerified()),
                view.getFirstName(),
                view.getLastName(),
                view.getAttributes() == null ? Map.of() : Map.copyOf(view.getAttributes())
        );
    }

    public List<String> attribute(String name) {
        String value = attributes.get(name);
        return value == null ? List.of() : List.of(value);
    }
}
