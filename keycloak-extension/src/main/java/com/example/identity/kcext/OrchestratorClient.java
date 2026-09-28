package com.example.identity.kcext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Talks to the orchestrator's kc facade (docs/05-api.md Abschnitt 3). Every call carries a freshly
 * signed peer-auth assertion instead of a bearer token: Keycloak itself is the caller identity
 * (docs/12-entscheidungen.md ADR-7).
 */
final class OrchestratorClient {

    /**
     * Unbekannte Felder werden ueberlesen: Die Extension laeuft in einem eigenen Image und muss
     * gegen einen neueren Orchestrator weiterarbeiten. Ein neues Feld darf sie nicht stoppen, ein
     * umbenanntes oder entferntes faellt beim Compilieren auf.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /**
     * One client for every request: Keycloak builds an OrchestratorClient per request, and a client
     * of its own each time would mean a thread pool and no connection reuse per request.
     */
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final HttpClient http = HTTP;
    private final String baseUrl;
    private final PeerAuthAssertionSigner signer;
    private final OrchestratorResponseVerifier verifier;

    OrchestratorClient(String baseUrl, String issuer, String audience, com.nimbusds.jose.jwk.ECKey signingKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.signer = new PeerAuthAssertionSigner(issuer, audience, signingKey);
        // The orchestrator answers as the audience of our assertions, addressed to their issuer (us).
        this.verifier = OrchestratorResponseVerifier.forOrchestrator(this.baseUrl, audience, issuer);
    }

    /**
     * PATCH .../kc/channels/{channelSessionId}, upsert semantics. Signed with {@code channelSessionId}
     * as the peer-auth anchor (docs/02-domaenenmodell.md Abschnitt 1): unique per flow run, so two
     * tabs stepping up the same SSO session never share an anchor. {@code durableKcSessionId} is
     * Keycloak's UserSessionModel id; it travels only with {@code restoreData}, so the server can
     * check that token was minted for this browser's durable identity.
     */
    ChannelResponse upsertChannel(
            String channelSessionId,
            Long accountId,
            String targetAcr,
            List<AmrEntry> amr,
            String restoreData,
            String durableKcSessionId,
            List<String> availableTools,
            String intent
    ) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/kc/channels/" + channelSessionId;
        ObjectNode body = MAPPER.createObjectNode();
        if (accountId != null) body.put("accountId", accountId);
        if (targetAcr != null) body.put("targetAcr", targetAcr);
        // Only counts on the channel's first call; omitted means kc_select_method.
        if (intent != null && !intent.isBlank()) body.put("intent", intent);
        // Only counts on the channel's first call, but is sent every time: this client cannot
        // cheaply know whether the channel already exists.
        if (availableTools != null && !availableTools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("availableTools");
            availableTools.forEach(toolsArray::add);
        }
        if (amr != null && !amr.isEmpty()) {
            ArrayNode amrArray = body.putArray("amr");
            for (AmrEntry entry : amr) {
                ObjectNode entryNode = amrArray.addObject();
                entryNode.put("nativeToolId", entry.nativeToolId());
                entryNode.put("amrSourceId", entry.amrSourceId());
            }
        }
        if (restoreData != null) {
            body.put("restoreData", restoreData);
            body.put("kcSessionId", durableKcSessionId);
        }
        JsonNode response = send("PATCH", path, channelSessionId, body);
        return ChannelResponse.from(response);
    }

    /**
     * GET .../kc/channels/{channelSessionId}/restore-data, the end-of-flow hook. Signed with the
     * {@code channelSessionId} anchor; {@code durableKcSessionId} only names what the returned
     * token is bound to. {@code sessionExpiresAt} (epoch seconds) caps the channel's expiry (ADR-43).
     */
    String restoreData(String channelSessionId, String durableKcSessionId, long sessionExpiresAt) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/kc/channels/" + channelSessionId + "/restore-data?kcSessionId=" + urlEncode(durableKcSessionId)
                + "&sessionExpiresAt=" + sessionExpiresAt;
        JsonNode response = send("GET", path, channelSessionId, null);
        JsonNode restoreData = response.path("restoreData");
        return restoreData.isTextual() ? restoreData.asText() : null;
    }

    /**
     * POST .../channels/{channelSessionId}/enrollments: starts MANAGE_AUTH_METHODS on an
     * authenticated channel (docs/05-api.md, "Anmeldeverfahren verwalten im Web-Kanal"). The
     * binding-key guard accepts a peer-auth assertion like a DPoP proof.
     */
    ChannelResponse startEnrollments(String channelSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/enrollments";
        return ChannelResponse.from(send("POST", path, channelSessionId, MAPPER.createObjectNode()));
    }

    /**
     * GET .../channels/{channelSessionId}/methods: the active methods for the management screen
     * (docs/05-api.md, "Anmeldeverfahren verwalten im Web-Kanal").
     */
    List<MethodView> getMethods(String channelSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/methods";
        JsonNode response = send("GET", path, channelSessionId, null);
        List<MethodView> methods = new ArrayList<>();
        response.path("methods").forEach(m -> methods.add(MethodView.from(m)));
        return methods;
    }

    /**
     * DELETE .../channels/{channelSessionId}/methods/{methodInstanceId}: deactivates one method.
     * Like enrollment, it may first ask for a loa2 step-up.
     */
    ChannelResponse deactivateMethod(String channelSessionId, String methodInstanceId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/methods/" + methodInstanceId;
        return ChannelResponse.from(send("DELETE", path, channelSessionId, null));
    }

    /** Same facade-neutral tool endpoints the App channel uses (docs/05-api.md Abschnitt 3). */
    ChannelResponse activateTool(String channelSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/tools/" + toolId;
        return ChannelResponse.from(send("POST", path, channelSessionId, MAPPER.createObjectNode()));
    }

    /**
     * {@code channelSessionId} is only used for signing. The URL carries no channelSessionId for
     * {@code htu} to bind, and toolSessionId is not self-authorizing (docs/02-domaenenmodell.md
     * Abschnitt 1), so the anchor claim alone ties this call to the right channel.
     */
    ChannelResponse patchTool(String channelSessionId, String toolSessionId, String toolId, Map<String, String> fields) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId;
        ObjectNode body = MAPPER.createObjectNode();
        fields.forEach(body::put);
        return ChannelResponse.from(send("PATCH", path, channelSessionId, body));
    }

    /**
     * GET .../tools/{toolSessionId}/{toolId}: the tool's current step, read only. Same anchor
     * convention as {@link #patchTool}.
     */
    ChannelResponse readTool(String channelSessionId, String toolSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId;
        return ChannelResponse.from(send("GET", path, channelSessionId, null));
    }

    /** DELETE .../tools/{toolSessionId}/{toolId} - declines the running tool ("Abbrechen"). */
    ChannelResponse abandonTool(String channelSessionId, String toolSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId;
        return ChannelResponse.from(send("DELETE", path, channelSessionId, null));
    }

    /** POST .../tools/{toolSessionId}/{toolId}/back - leaves the running tool without declining it ("Zurück"). */
    ChannelResponse backFromTool(String channelSessionId, String toolSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId + "/back";
        return ChannelResponse.from(send("POST", path, channelSessionId, MAPPER.createObjectNode()));
    }

    /**
     * DELETE .../channels/{channelSessionId}/journey: abandons the active journey on a select screen,
     * before any tool was picked. A cancelled sub-journey resumes its parent, a cancelled top-level
     * journey restarts the channel's entry journey; the response's {@code next} says what to render.
     */
    ChannelResponse abandonJourney(String channelSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/journey";
        return ChannelResponse.from(send("DELETE", path, channelSessionId, null));
    }

    /**
     * POST .../channels/{channelSessionId}/answer: the yes/no reply to a prompt
     * (next.context=prompt, next.step=confirm). {@code answer} is {@code "accept"} or {@code "decline"}.
     */
    ChannelResponse answer(String channelSessionId, String answer) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/answer";
        ObjectNode body = MAPPER.createObjectNode();
        body.put("answer", answer);
        return ChannelResponse.from(send("POST", path, channelSessionId, body));
    }

    /**
     * Stateless password verify/set for Keycloak's native password credential. There is no channel
     * here: the account id goes into the URL path, which {@code htu} binds, and the assertion's
     * {@code channel_anchor} claim carries the same account id. The orchestrator's
     * {@code MgmtPasswordController} checks both match.
     */
    boolean verifyPassword(long accountId, String password) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/auth-password/mgmt/" + accountId;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("password", password);
        JsonNode response = send("POST", path, String.valueOf(accountId), body);
        return response.path("valid").asBoolean(false);
    }

    /**
     * Reports that Keycloak ended session {@code kcSessionId} of {@code accountId}, for the sign-in
     * log (ADR-39). The Web channel's logout is Keycloak's own; the orchestrator would not learn of
     * it otherwise. Same anchor convention as the password calls.
     */
    void reportSignOut(long accountId, String kcSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/kc/accounts/" + accountId + "/sign-outs?kcSessionId=" + urlEncode(kcSessionId);
        send("POST", path, String.valueOf(accountId), null);
    }

    /** See {@link #verifyPassword(long, String)} - same anchor convention. */
    void setPassword(long accountId, String newPassword) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/enroll-password/mgmt/" + accountId;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("newPassword", newPassword);
        send("POST", path, String.valueOf(accountId), body);
    }

    /**
     * The account behind a federated user, read by id; the anchor names the account like the
     * password endpoints do. {@code null} when there is no such account.
     */
    KcAccount accountById(long accountId) throws IOException, InterruptedException {
        return lookup("/orchestrator/api/v1/kc/accounts/" + accountId, String.valueOf(accountId));
    }

    /** By exact email - never a list; {@code null} when no account holds this address. */
    KcAccount accountByEmail(String email) throws IOException, InterruptedException {
        return lookup("/orchestrator/api/v1/kc/accounts?email=" + urlEncode(email), ACCOUNT_LOOKUP_ANCHOR);
    }

    /** By username ({@code account-<id>} or the email); {@code null} when there is none. */
    KcAccount accountByUsername(String username) throws IOException, InterruptedException {
        return lookup("/orchestrator/api/v1/kc/accounts?username=" + urlEncode(username), ACCOUNT_LOOKUP_ANCHOR);
    }

    /** The peer-auth anchor of a search by address - {@code KcAccountLookupController.LOOKUP_ANCHOR}. */
    private static final String ACCOUNT_LOOKUP_ANCHOR = "account-lookup";

    private KcAccount lookup(String path, String anchor) throws IOException, InterruptedException {
        try {
            return KcAccount.from(send("GET", path, anchor, null));
        } catch (OrchestratorApiException e) {
            if (e.status == 404) return null;
            throw e;
        }
    }

    /** A text bundle answer: 200 with wordings, or 304 while [etag] is current. */
    record TextsAnswer(int status, String etag, String body) {
    }

    /**
     * GET .../texts/{language}, signed and verified like every other call: the wordings decide what
     * the login page says, so nobody on the hop may choose them.
     */
    TextsAnswer texts(String language, String etag) throws IOException, InterruptedException {
        String url = baseUrl + "/orchestrator/api/v1/texts/" + urlEncode(language);
        String assertion = signer.sign("GET", url, TEXTS_ANCHOR);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + assertion)
                .GET();
        if (etag != null) builder.header("If-None-Match", etag);
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        verifier.verify(
                response.headers().firstValue(OrchestratorResponseVerifier.HEADER).orElse(null),
                PeerAuthAssertionSigner.jtiOf(assertion),
                response.statusCode(),
                response.body());
        return new TextsAnswer(response.statusCode(), response.headers().firstValue("ETag").orElse(""),
                new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final String TEXTS_ANCHOR = "texts";

    private JsonNode send(String method, String path, String channelSessionId, JsonNode body) throws IOException, InterruptedException {
        String url = baseUrl + path;
        String assertion = signer.sign(method, url, channelSessionId);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + assertion)
                .header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        builder.method(method, publisher);

        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        // Before anything in the answer is believed - error or not.
        verifier.verify(
                response.headers().firstValue(OrchestratorResponseVerifier.HEADER).orElse(null),
                PeerAuthAssertionSigner.jtiOf(assertion),
                response.statusCode(),
                response.body());
        String answer = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new OrchestratorApiException(response.statusCode(), answer);
        }
        if (answer.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(answer);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Mirrors ActiveMethodView (tool_api/Envelope.kt) - id/method/label, nothing more. */
    record MethodView(String id, String method, String label) {
        static MethodView from(JsonNode json) {
            JsonNode labelNode = json.get("label");
            return new MethodView(
                    json.path("id").asText(null),
                    json.path("method").asText(null),
                    labelNode != null && labelNode.isTextual() ? labelNode.asText() : null
            );
        }
    }

    record AmrEntry(String nativeToolId, String amrSourceId) {
    }

    /** Mirrors AmrEntry (docs/05-api.md Abschnitt 3) - just the two stable ids, never method/loa directly. */
    static final class OrchestratorApiException extends IOException {
        final int status;
        final String errorCode;
        /** What the user is told - a text reference, resolved per login language ({@link #message}). */
        final JsonNode text;

        OrchestratorApiException(int status, String body) {
            super("Orchestrator call failed: " + status + " " + body);
            this.status = status;
            String parsedCode = null;
            JsonNode parsedText = null;
            // Field names from the generated contract model, so a rename breaks the compile. Read as
            // a tree rather than as that model: its enum rejects a code this build does not know,
            // and the contract says a client must expect new codes.
            try {
                JsonNode node = MAPPER.readTree(body);
                String errorField = com.example.identity.kcext.api.model.ErrorResponse.JSON_PROPERTY_ERROR;
                String textField = com.example.identity.kcext.api.model.ErrorResponse.JSON_PROPERTY_TEXT;
                if (node.hasNonNull(errorField)) parsedCode = node.get(errorField).asText();
                if (node.hasNonNull(textField)) parsedText = node.get(textField);
            } catch (Exception ignored) {
                // Not JSON - no text to show; callers fall back to their own.
            }
            this.errorCode = parsedCode;
            this.text = parsedText;
        }

        /** The error in the login's language, or null when the orchestrator sent no text. */
        String message(org.keycloak.models.KeycloakSession session) {
            return OrchestratorTexts.resolve(session, text);
        }
    }

    /** Parsed view of ChannelResponse (tool_api/Envelope.kt) - only the fields this plugin reads. */
    record ChannelResponse(
            String channelSessionId,
            String channelState,
            Next next,
            Map<String, JsonNode> stepData,
            Map<String, JsonNode> demo,
            Long authDataAccountId,
            String authDataAcr,
            Map<String, String> authDataAmr
    ) {
        /**
         * Baut die flache Sicht aus den generierten Vertragsmodellen (api/openapi.yaml), damit eine
         * Vertragsaenderung beim Compilieren auffaellt.
         *
         * <p>{@code stepData} und {@code demo} bleiben offene JsonNodes: Die Renderer picken einzelne
         * Schluessel heraus, und der generierte stepData-Union-Typ wirft bei einer unbekannten Form
         * (siehe ContractModelTest). Die Extension muss einen neueren Orchestrator ueberstehen.
         */
        static ChannelResponse from(JsonNode json) {
            com.example.identity.kcext.api.model.ChannelResponse wire;
            try {
                wire = MAPPER.treeToValue(stripOpenBags(json), com.example.identity.kcext.api.model.ChannelResponse.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Antwort des Orchestrators passt nicht zum Vertrag", e);
            }

            Map<String, JsonNode> stepData = new LinkedHashMap<>();
            json.path("stepData").properties().forEach(e -> stepData.put(e.getKey(), e.getValue()));

            // Sibling of stepData, passed through unchanged: different tools prefill different keys.
            Map<String, JsonNode> demo = new LinkedHashMap<>();
            json.path("demo").properties().forEach(e -> demo.put(e.getKey(), e.getValue()));

            var channel = wire.getChannel();
            var wireNext = wire.getNext();
            var authData = wire.getAuthData();

            return new ChannelResponse(
                    // Der Vertrag fuehrt die beiden Session-Ids als uuid, die flache Sicht als String -
                    // die Extension reicht sie nur als Pfadsegment weiter und parst sie nie.
                    channel == null ? null : Objects.toString(channel.getChannelSessionId(), null),
                    channel == null ? null : channel.getState(),
                    wireNext == null ? null : new Next(
                            wireNext.getType(),
                            wireNext.getToolId(),
                            wireNext.getContext(),
                            wireNext.getStep(),
                            Objects.toString(wireNext.getToolSessionId(), null)
                    ),
                    stepData,
                    demo,
                    authData == null ? null : authData.getAccountId(),
                    authData == null ? null : authData.getAcr(),
                    authData == null || authData.getAmr() == null ? Map.of() : authData.getAmr()
            );
        }

        /**
         * Entfernt die beiden offenen Beutel, bevor das typisierte Modell sie sieht. Sonst liesse der
         * stepData-Union-Typ jede Antwort mit unbekannter Form scheitern.
         */
        private static JsonNode stripOpenBags(JsonNode json) {
            if (!(json instanceof ObjectNode object)) {
                return json;
            }
            ObjectNode copy = object.deepCopy();
            copy.remove("stepData");
            copy.remove("demo");
            return copy;
        }

        List<String> stepDataOptions() {
            List<String> options = new ArrayList<>();
            stepData.getOrDefault("options", MAPPER.createArrayNode()).forEach(n -> options.add(n.asText()));
            return options;
        }

        /** The failed attempt's text reference, if the last attempt failed. */
        JsonNode stepDataError() {
            return stepData.get("error");
        }
    }

    record Next(String type, String toolId, String context, String step, String toolSessionId) {
        static Next from(JsonNode json) {
            return new Next(
                    json.path("type").asText(null),
                    json.path("toolId").asText(null),
                    json.path("context").asText(null),
                    json.path("step").asText(null),
                    json.path("toolSessionId").asText(null)
            );
        }

        boolean isTool() {
            return "tool".equals(type);
        }

        boolean isSelectMethod() {
            // "selectIdentificationMethod" is REGISTER's identification choice; it renders the same
            // selection screen as "selectMethod" (docs/04-orchestrierung.md #4).
            return "orchestrator".equals(type) && ("selectMethod".equals(step) || "selectIdentificationMethod".equals(step));
        }

        boolean isAuthenticated() {
            return "orchestrator".equals(type) && "authenticated".equals(step);
        }

        boolean isConfirm() {
            return "orchestrator".equals(type) && "confirm".equals(step);
        }
    }
}
