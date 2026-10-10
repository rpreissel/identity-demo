package com.example.identity.kcext.model;

import com.example.identity.kcext.api.model.KeycloakInvitationView;
import java.util.List;
import java.util.Map;

/**
 * An invitation as the orchestrator shows it to Keycloak ({@code KeycloakInvitationLookupController}) -
 * read through on every lookup, never stored here.
 */
public record KcInvitation(
        String invitation,
        String username,
        boolean enabled,
        String firstName,
        String lastName,
        Map<String, String> attributes
) {
    /** Read through the contract model, so a renamed field breaks the compile, not a login. */
    public static KcInvitation from(KeycloakInvitationView view) {
        return new KcInvitation(
                view.getInvitation(),
                view.getUsername(),
                Boolean.TRUE.equals(view.getEnabled()),
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
