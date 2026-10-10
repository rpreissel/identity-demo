package com.example.identity.kcext.webtool;

import com.example.identity.kcext.client.OrchestratorSettings;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/**
 * What a {@link WebToolRenderer#render} needs for the current step; the counterpart to the App
 * channel's {@code ToolRenderContext}. {@code stepData} and {@code demo} pass through unchanged,
 * since each tool picks its own keys. {@code settings} is the resolved realm configuration rather
 * than the whole session. {@code submittedFields} are the field names of the answered form post: a
 * {@code failed-attempt} step carries no {@code missingFields}, so a multi-page renderer shows the
 * page the attempt came from. {@code statusUrl} is where a waiting page asks whether its step is
 * still current ({@code QrWaitStatusResourceProvider}). {@code loginHint} is the client's
 * {@code login_hint}, for a page that asks who signs in.
 */
public record WebToolRenderContext(
        String toolId,
        String step,
        Map<String, JsonNode> stepData,
        Map<String, JsonNode> demo,
        String error,
        OrchestratorSettings settings,
        Set<String> submittedFields,
        String statusUrl,
        String loginHint
) {
}
