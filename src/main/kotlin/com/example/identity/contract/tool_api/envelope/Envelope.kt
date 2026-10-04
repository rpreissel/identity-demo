package com.example.identity.contract.tool_api.envelope

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.texts.Text
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "One active authentication method instance. `id` addresses it for " +
        "DELETE .../methods/{id} - method name alone isn't unique when a method allows multiple " +
        "instances (e.g. several active `device` entries, one per physical device). `label` is a " +
        "user-chosen display name, set only for multi-instance methods; `null` for singleton ones " +
        "(email/sms/password), which the client labels from `method` itself. `enrolledUnderAcr`/" +
        "`maxAcr`/`effectiveAcr`/`factorTypes` surface the ADR-5 three-way cap (docs/12-" +
        "entscheidungen.md): `effectiveAcr` is " +
        "`min(enrolledUnderAcr, maxAcr)`, the level this method can actually contribute right now, " +
        "which can be lower than the tool's own declared `maxAcr` if it was enrolled while the " +
        "session had proven less."
)
data class ActiveMethodView(
    @field:Schema(example = "7f3e2b1a-0c9d-4e8f-8a1b-2c3d4e5f6a7b")
    val id: String,
    @field:Schema(example = "sms")
    val method: String,
    @field:Schema(example = "Laptop")
    val label: String? = null,
    @field:Schema(example = "[\"POSSESSION\"]")
    // List, not Set: a Set would make the generated client type a JS Set, which JSON.parse never
    // produces.
    val factorTypes: List<FactorType>? = null,
    @field:Schema(example = "loa2", description = "This tool's own declared ceiling - never account/session-specific.")
    val maxAcr: String? = null,
    @field:Schema(example = "loa1", description = "The level the session had already proven at the moment this method was enrolled (ADR-5) - caps effectiveAcr below maxAcr if lower.")
    val enrolledUnderAcr: String? = null,
    @field:Schema(example = "loa1", description = "min(enrolledUnderAcr, maxAcr) - what this method actually contributes today.")
    val effectiveAcr: String? = null,
    @field:Schema(description = "Whether the credential can be changed in place (POST .../methods/{id}/changes): a new password, a new number.")
    val changeable: Boolean = false
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelBlock(
    val channelSessionId: ChannelSessionId,
    @field:Schema(
        description = "Which facade this channel was opened through - APP (DPoP) or WEB " +
            "(docs/02-domaenenmodell.md Abschnitt 1). Fixed for the channel's whole lifetime.",
        example = "APP"
    )
    val channelType: String,
    @field:Schema(example = "AUTHENTICATED")
    val state: String,
    @field:Schema(
        description = "Whether this session has already proven at least one factor (an " +
            "identification or a method) - what cancelling the running journey would throw away. " +
            "Lets a client ask \"really discard?\" only when there is something to lose. Set on " +
            "every response, tool responses included; says nothing about which factor.",
        example = "false"
    )
    val hasProvenFactor: Boolean,
    @field:Schema(example = "loa2")
    val currentAcr: String? = null,
    @field:Schema(example = "[\"sms\", \"password\"]")
    val currentAmr: List<String>? = null,
    @field:Schema(
        description = "All active authentication methods on the account, regardless of whether " +
            "this session's currentAmr proved them. currentAmr is session evidence (what THIS " +
            "channel actually proved); activeMethods is the account's full standing method list, " +
            "unfiltered by device - a lost/stolen device's credential must be removable from any " +
            "authenticated session, not only from that device itself."
    )
    val activeMethods: List<ActiveMethodView>? = null
)

/**
 * What the Keycloak facade's `OrchestratorAuthenticator` writes into Keycloak's session notes on every
 * response (docs/05-api.md Abschnitt 3), for `WEB` channels only. Not gated on a proven
 * factor: Keycloak sets its user context from `subject` as soon as it is known.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuthData(
    @field:Schema(example = "loa2")
    val acr: String? = null,
    @field:Schema(
        description = "Method -> who proved it: \"orchestrator\" for a completed orchestrator " +
            "tool, \"kc\" for evidence a native Keycloak authenticator already established " +
            "(docs/05-api.md Abschnitt 3). Informational only - the orchestrator alone " +
            "still resolves the combined acr above, regardless of source.",
        example = "{\"password\": \"kc\", \"sms\": \"orchestrator\"}"
    )
    val amr: Map<String, String>? = null,
    /** Whom the channel is signed in as, once it is known: an account or an invitation. */
    val subject: AuthSubject? = null
)

/**
 * The subject of a Web channel. An account in the ordinary case; an invitation after a
 * one-time password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md), whose id is the SHA-256 of that
 * password. Keycloak signs in the matching user of its own federation, never an account for an invitation.
 */
data class AuthSubject(
    val type: AuthSubjectType,
    @field:Schema(example = "42")
    val id: String,
)

enum class AuthSubjectType {
    @JsonProperty("account") ACCOUNT,
    @JsonProperty("invitation") INVITATION,
}

/**
 * The response envelope for every channel- and tool-level endpoint. `channel` carries the
 * current channel state; `next` addresses the client's next step; a tool completing never
 * requires a follow-up `GET /channels` - `channel` already reflects the post-outcome state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelResponse(
    val channel: ChannelBlock,
    val next: Next? = null,
    @field:Schema(
        description = "Whatever the current step needs to render. `kind` names the shape - see StepData."
    )
    val stepData: StepData? = null,
    @field:Schema(description = "Demo-only correlation IDs, never part of the production contract.")
    val demo: DemoInfo? = null,
    @field:Schema(description = "WEB channels only (docs/05-api.md Abschnitt 3) - never present for APP.")
    val authData: AuthData? = null
)

/**
 * Debug-only view of one journey in the channel's running chain, outermost first. Plain strings
 * instead of the orchestrator's types, because `tool_api` must not depend on `orchestrator`
 * (docs/03-tool-architektur.md #4).
 */
data class JourneyDebugStep(
    @field:Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    val journeyId: String,
    @field:Schema(example = "DELETE_ACCOUNT")
    val intent: String,
    @field:Schema(example = "SUSPENDED")
    val lifecycle: String,
    @field:Schema(example = "ConfirmPending")
    val stateType: String,
    @field:Schema(
        description = "Demo-only: why this journey's current step looks the way it does - either " +
            "why its tool became the automatic choice, or why a selection among several is being " +
            "shown at all. Null whenever the step already explains itself (e.g. a Prompt), never " +
            "part of the production contract.",
    )
    val note: Text? = null,
    @field:Schema(
        description = "Demo-only: what this journey's current position is for - the orchestrator's " +
            "reason for being here at all, set for every journey in the chain. Never part of the " +
            "production contract.",
    )
    val purpose: Text? = null
)

/** Demo-only snapshot of a session's standing (DemoInfo.session). */
data class DemoSession(
    @field:Schema(description = "Whether the session is signed in (channel state AUTHENTICATED).")
    val authenticated: Boolean,
    @field:Schema(description = "\"Vorname Name\" of the person behind the account, if one is bound.", example = "Max Muster")
    val personName: String? = null,
    @field:Schema(example = "loa1")
    val acr: String? = null,
    @field:Schema(example = "[\"sms\"]")
    val amr: List<String> = emptyList()
)

/**
 * Demo-only values a tool handler attached (e.g. a plaintext `tan`), flattened into the JSON
 * object alongside [accountId]/[personId] so the client can read `demo.tan` regardless of which
 * tool produced it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
// additionalProperties, because @JsonAnyGetter writes [values] flat onto this object (`demo.tan`).
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
data class DemoInfo(
    @field:Schema(example = "42")
    val accountId: AccountId? = null,
    @field:Schema(example = "P000000001")
    val personId: PartnerNumber? = null,
    @field:Schema(
        description = "The running journey chain for this channel, outermost first - see " +
            "[JourneyDebugStep]. Empty once nothing is running."
    )
    val journeys: List<JourneyDebugStep> = emptyList(),
    @field:Schema(
        description = "Demo-only: who this session belongs to and what it has proven so far - in " +
            "every response, tool responses included, so a demo view can show it at any step. The " +
            "production contract keeps these on the channel resource only (ChannelBlock)."
    )
    val session: DemoSession? = null,
    // Hidden: the entries are written flat, as the class-level additionalProperties states.
    @field:Schema(hidden = true)
    @get:JsonAnyGetter val values: Map<String, Any?> = emptyMap()
)
