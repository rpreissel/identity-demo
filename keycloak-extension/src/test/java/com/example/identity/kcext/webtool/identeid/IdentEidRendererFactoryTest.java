package com.example.identity.kcext.webtool.identeid;

import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ident-eid keeps one step "input" (docs/06-ablaeufe.md #6); which of the two pages the renderer
 * shows follows {@code missingFields}, and after a failed attempt the page the form came from.
 */
class IdentEidRendererFactoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WebToolRenderContext context(List<String> missingFields, Set<String> submittedFields) {
        Map<String, JsonNode> stepData = missingFields == null ? Map.of() : Map.of("missingFields", JSON.valueToTree(missingFields));
        return new WebToolRenderContext("ident-eid", "input", stepData, Map.of(), null, null, submittedFields, null);
    }

    @Test
    void showsTheCardWhileAnyCardFieldIsMissing() {
        assertTrue(IdentEidRendererFactory.cardPage(context(List.of("postalCode", "restrictedId"), Set.of())));
    }

    @Test
    void showsThePinOnceOnlyThePinIsMissing() {
        assertFalse(IdentEidRendererFactory.cardPage(context(List.of("pin"), Set.of())));
    }

    @Test
    void staysOnTheCardAfterRejectedCardData() {
        assertTrue(IdentEidRendererFactory.cardPage(context(null, Set.of("familyName", "postalCode", "restrictedId"))));
    }

    @Test
    void staysOnThePinAfterARejectedPin() {
        assertFalse(IdentEidRendererFactory.cardPage(context(null, Set.of("pin"))));
    }
}
