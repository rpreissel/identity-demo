package com.example.identity.core.orchestrator.api.v1.keycloak

import com.example.identity.contract.tool_api.envelope.AuthSubject
import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "Upsert body for the Keycloak facade's one facade-specific endpoint (docs/05-api.md " +
        "Abschnitt 3). All fields are optional. subject is whom Keycloak already knows " +
        "(sub vorhanden) - an account binds the channel immediately, once, never overwritten by a " +
        "later call. targetAcr is Keycloak's requested LoA level, already " +
        "translated into an orchestrator ACR string, and only raises the channel's floor, never " +
        "lowers it. Keycloak proves nothing itself: every sign-in step is an orchestrator tool " +
        "(ADR-58)."
)
data class KeycloakChannelUpsertRequest(
    @field:Schema(
        description = "Whom Keycloak knows this flow run belongs to: an account or an invitation " +
            "(ADR-48), the same shape as authData.subject in the answer. A channel already bound to " +
            "another subject refuses it (409); an account binds a channel that has none, an " +
            "invitation never does - only its own proof binds it."
    )
    val subject: AuthSubject? = null,
    @field:Schema(example = "loa2")
    val targetAcr: String? = null,
    @field:Schema(
        description = "Keycloak's own, durable UserSessionModel id, when the browser already holds a " +
            "Keycloak session - deliberately NOT read off the peer-auth assertion (the assertion's Keycloak " +
            "binding is always THIS flow run's own channelSessionId, docs/02-domaenenmodell.md Abschnitt 1). " +
            "On a channel's first call, what earlier flow runs of that session proved seeds the channel " +
            "(ADR-59); ignored on later calls."
    )
    val kcSessionId: String? = null,
    @field:Schema(
        description = "The Web channel's own declaration of which toolIds its Keycloak theme can " +
            "render (one com.example.identity.kcext.webtool.WebToolRenderer factory " +
            "per toolId, registered via META-INF/services) - the Keycloak facade's counterpart to the App " +
            "channel's own availableTools (POST /channels). Only read on this channel's first call " +
            "(a later upsert resumes the already-persisted set); a channel-anonymous caller that " +
            "omits this gets none of the orchestrator's tools, never all of them."
    )
    val availableTools: List<String>? = null,
    @field:Schema(
        description = "Only read on this channel's first call, same restriction as availableTools " +
            "- the Keycloak facade's own, deliberately narrow counterpart to the App facade's `intent` " +
            "request parameter (docs/05-api.md #\"POST /app/channels: intent-Parameter\"). Omitted " +
            "(or null) means web_select_method, the existing login/step-up behaviour. Only " +
            "web_select_method and register are accepted here - unlike the App facade, not every " +
            "AuthIntent.isEntryIntent value: fast_access/lookup_login assume an APP-shaped channel " +
            "this facade never has.",
        example = "register"
    )
    val intent: String? = null
)
