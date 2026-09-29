package com.example.identity.kcext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The orchestrator's texts for the web channel (ADR-33): resolves text references
 * ({@code {key, args, texts}}) in the login's language against {@code GET .../texts/{lang}}. One copy
 * per orchestrator and language, revalidated by ETag at most once a minute. If the orchestrator is
 * unreachable, the last copy stays in use; without any copy a reference shows its template or id.
 */
final class OrchestratorTexts {

    private static final Logger LOG = Logger.getLogger(OrchestratorTexts.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REVALIDATE_AFTER = Duration.ofMinutes(1);
    private static final List<String> SUPPORTED = List.of("de", "en");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    private record Bundle(String etag, Map<String, String> texts, long checkedAtNanos) {
    }

    private static final Map<String, Bundle> BUNDLES = new ConcurrentHashMap<>();

    private OrchestratorTexts() {
    }

    /** The login's language if the orchestrator writes it, otherwise German. */
    static String language(KeycloakSession session) {
        Locale locale = session.getContext().resolveLocale(null);
        String language = locale != null ? locale.getLanguage() : "de";
        return SUPPORTED.contains(language) ? language : "de";
    }

    /** {@code ref} in the login's language; null for a missing reference. */
    static String resolve(KeycloakSession session, JsonNode ref) {
        if (ref == null || ref.isNull() || !ref.hasNonNull("key")) return null;
        return resolve(bundle(OrchestratorSettings.of(session), language(session)), ref);
    }

    static String resolve(Map<String, String> texts, JsonNode ref) {
        // Not worded yet (ADR-33): the orchestrator then sends the template along - shown instead of the key.
        String fallback = ref.hasNonNull("template") ? ref.get("template").asText() : ref.get("key").asText();
        String wording = texts.getOrDefault(ref.get("key").asText(), fallback);
        Matcher matcher = PLACEHOLDER.matcher(wording);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            JsonNode nested = ref.path("texts").get(name);
            JsonNode plain = ref.path("args").get(name);
            String value;
            if (nested != null && nested.isArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode item : nested) {
                    if (!joined.isEmpty()) joined.append(", ");
                    joined.append(resolve(texts, item));
                }
                value = joined.toString();
            } else if (plain != null && !plain.isNull()) {
                value = plain.asText();
            } else {
                value = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Map<String, String> bundle(OrchestratorSettings settings, String language) {
        String cacheKey = settings.orchestratorBaseUrl() + "|" + language;
        Bundle cached = BUNDLES.get(cacheKey);
        if (cached != null && System.nanoTime() - cached.checkedAtNanos() < REVALIDATE_AFTER.toNanos()) {
            return cached.texts();
        }
        try {
            OrchestratorClient.TextsAnswer response = settings.newClient().texts(language, cached == null ? null : cached.etag());
            Bundle next;
            if (response.status() == 304 && cached != null) {
                next = new Bundle(cached.etag(), cached.texts(), System.nanoTime());
            } else if (response.status() == 200) {
                Map<String, String> texts = MAPPER.readValue(response.body(), new TypeReference<>() {
                });
                next = new Bundle(response.etag(), texts, System.nanoTime());
            } else {
                throw new IllegalStateException("texts " + language + ": HTTP " + response.status());
            }
            BUNDLES.put(cacheKey, next);
            return next.texts();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("Orchestrator texts (%s) not available: %s", language, e.getMessage());
            return cached != null ? cached.texts() : Map.of();
        }
    }
}
