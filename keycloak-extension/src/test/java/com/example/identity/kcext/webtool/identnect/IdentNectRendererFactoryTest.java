package com.example.identity.kcext.webtool.identnect;

import com.example.identity.kcext.client.OrchestratorSettings;
import com.example.identity.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ident-nect in the web channel (docs/adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md): the activation names the step's action
 * URL as the way back, and the page links to the jump page as the browser reaches it.
 */
class IdentNectRendererFactoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OrchestratorSettings SETTINGS =
            new OrchestratorSettings("http://orchestrator:8080", "http://localhost:8080", "iss", "aud", null);

    private static WebToolRenderContext context(Map<String, JsonNode> stepData) {
        return new WebToolRenderContext("ident-nect", "redirect", stepData, Map.of(), null, SETTINGS, Set.of(), null);
    }

    @Test
    void activationNamesTheActionUrlAsTheReturnAddress() {
        String actionUrl = "https://kc/realms/Demo/login-actions/authenticate?session_code=c&execution=e&client_id=web&tab_id=t";

        assertEquals(Map.of("returnUri", actionUrl), new IdentNectRendererFactory().activationFields(() -> actionUrl));
    }

    @Test
    void aRetryNamesAFreshActionUrl_theActivationsCodeIsSpent() {
        String freshActionUrl = "https://kc/realms/Demo/login-actions/authenticate?session_code=fresh&execution=e";
        Map<String, String> form = Map.of("retry", "true");

        assertEquals(Map.of("returnUri", freshActionUrl), new IdentNectRendererFactory().actionFields(form::get, () -> freshActionUrl));
    }

    @Test
    void reportingACaseAsksForNoAddress() {
        Map<String, String> form = Map.of("nectCaseId", "5b1c");

        assertEquals(Map.of(), new IdentNectRendererFactory().actionFields(form::get, () -> {
            throw new AssertionError("no action URL wanted");
        }));
    }

    @Test
    void theJumpPageGetsTheOrchestratorsPublicOriginInFront() {
        Map<String, JsonNode> stepData = Map.of("jumpUrl", JSON.valueToTree("/nect/?case=5b1c"), "caseId", JSON.valueToTree("5b1c"));

        assertEquals("http://localhost:8080/nect/?case=5b1c", IdentNectRendererFactory.jumpUrl(context(stepData)));
    }

    @Test
    void anAbsoluteJumpUrlStaysAsItIs() {
        Map<String, JsonNode> stepData = Map.of("jumpUrl", JSON.valueToTree("https://nect.example/start?case=5b1c"));

        assertEquals("https://nect.example/start?case=5b1c", IdentNectRendererFactory.jumpUrl(context(stepData)));
    }

    @Test
    void aFailedAttemptHasNoJumpPage() {
        Map<String, JsonNode> stepData = Map.of("error", JSON.valueToTree(Map.of("key", "abgebrochen")));

        assertNull(IdentNectRendererFactory.jumpUrl(context(stepData)));
    }
}
