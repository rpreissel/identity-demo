package com.example.identity.kcext.federation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An invitation as the orchestrator shows it to Keycloak ({@code KcInvitationLookupController}) -
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
    public static KcInvitation from(JsonNode json) {
        Map<String, String> attributes = new LinkedHashMap<>();
        json.path("attributes").properties().forEach(e -> attributes.put(e.getKey(), e.getValue().asText()));
        return new KcInvitation(
                json.path("invitation").asText(),
                json.path("username").asText(),
                json.path("enabled").asBoolean(false),
                json.path("firstName").asText(null),
                json.path("lastName").asText(null),
                Map.copyOf(attributes)
        );
    }

    List<String> attribute(String name) {
        String value = attributes.get(name);
        return value == null ? List.of() : List.of(value);
    }
}
