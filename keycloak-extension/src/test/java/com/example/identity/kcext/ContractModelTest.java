package com.example.identity.kcext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.identity.kcext.api.model.ChannelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Die generierten Vertragsmodelle gegen echtes Antwort-JSON: Sie muessen die Antwort tragen, damit
 * ein umbenanntes Feld auffaellt statt still {@code null} zu liefern. Benennt auch die eine Stelle,
 * an der sie es nicht tun.
 */
class ContractModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialisiertEineVollstaendigeAntwort() throws Exception {
        String json = """
            {
              "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                          "channelType": "KEYCLOAK", "state": "STEP_UP_IN_PROGRESS"},
              "next": {"type": "tool", "toolId": "auth-sms", "step": "auth"},
              "authData": {"accountId": 42, "acr": "loa2", "amr": {"sms": "orchestrator"}}
            }
            """;
        ChannelResponse response = mapper.readValue(json, ChannelResponse.class);

        assertEquals("STEP_UP_IN_PROGRESS", response.getChannel().getState());
        assertEquals("auth-sms", response.getNext().getToolId());
        assertEquals(42L, response.getAuthData().getAccountId());
    }

    /**
     * Hier ist der generierte Client nicht vorwaertskompatibel: Die stepData-Union wirft bei einer
     * unbekannten Form. Die Extension muss einen neueren Orchestrator ueberstehen, deshalb liest
     * OrchestratorClient stepData als offenen JsonNode. Der Test schlaegt fehl, sobald die Union das
     * Problem nicht mehr hat.
     */
    @Test
    void unbekannteStepDataFormBrichtDieUnion() {
        String vonMorgen = """
            {"channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                         "channelType": "KEYCLOAK", "state": "STEP_UP_IN_PROGRESS"},
             "stepData": {"kind": "eine-form-von-morgen", "irgendwas": 1}}
            """;
        assertThrows(Exception.class, () -> mapper.readValue(vonMorgen, ChannelResponse.class));

        // Als offener Knoten gelesen ueberlebt dieselbe Antwort.
        assertNotNull(assertDoesNotThrowJson(vonMorgen).path("stepData").path("kind").asText(null));
    }

    /**
     * Der Weg des Clients: Huelle getypt, die beiden offenen Beutel als JsonNode. Die Antwort traegt
     * eine unbekannte stepData-Form und ein unbekanntes Feld in der Huelle.
     */
    @Test
    void ueberstehtEineAntwortVonMorgen() {
        String vonMorgen = """
            {
              "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                          "channelType": "KEYCLOAK", "state": "STEP_UP_IN_PROGRESS",
                          "einNeuesFeld": "spaeter dazugekommen"},
              "next": {"type": "tool", "toolId": "auth-sms", "step": "auth",
                       "toolSessionId": "7d2b1d7e-0000-4000-8000-000000000001"},
              "stepData": {"kind": "eine-form-von-morgen", "prompt": "Bitte bestaetigen"},
              "demo": {"tan": "123456"},
              "authData": {"accountId": 42, "acr": "loa2", "amr": {"sms": "orchestrator"}}
            }
            """;
        OrchestratorClient.ChannelResponse flach = OrchestratorClient.ChannelResponse.from(assertDoesNotThrowJson(vonMorgen));

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", flach.channelSessionId());
        assertEquals("STEP_UP_IN_PROGRESS", flach.channelState());
        assertEquals("auth-sms", flach.next().toolId());
        assertEquals("7d2b1d7e-0000-4000-8000-000000000001", flach.next().toolSessionId());
        assertEquals(42L, flach.authDataAccountId());
        assertEquals("orchestrator", flach.authDataAmr().get("sms"));
        // Die beiden Beutel kommen unveraendert durch, auch in einer Form von morgen.
        assertEquals("Bitte bestaetigen", flach.stepData().get("prompt").asText());
        assertEquals("123456", flach.demo().get("tan").asText());
    }

    private JsonNode assertDoesNotThrowJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("readTree sollte jede Antwort lesen koennen", e);
        }
    }
}
