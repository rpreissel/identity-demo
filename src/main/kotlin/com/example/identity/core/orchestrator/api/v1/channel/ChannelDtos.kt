package com.example.identity.core.orchestrator.api.v1.channel

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import com.example.identity.core.orchestrator.domain.AuthIntent

@Schema(
    description = "requiredAcr is a lower bound only. Always creates a brand-new ChannelSession for this device " +
        "(docs/02-domaenenmodell.md #3) - DPoP proves the device, never a lookup key for resuming a session. To end " +
        "a previous session first (logout), call DELETE .../channels/{channelSessionId} before this."
)
data class ChannelCreateRequest(
    @field:Schema(example = "loa2")
    val requiredAcr: String? = null,
    @field:Schema(
        description = "The entry intent's own name, case-insensitively (AuthIntent.fromRequest) - no separate wire " +
            "vocabulary. Omitted/fast_access (default): DeviceAccountLink found -> LOGIN, else REGISTRATION. " +
            "lookup_login: always offers lookup-based login (email + credential), even on a linked device. " +
            "register: always starts fresh REGISTRATION, even on a linked device (second account). " +
            "confirm_peer_login: a cold app approves a waiting web login (QR pairing) for the device's linked account.",
        example = "register",
        allowableValues = ["fast_access", "register", "lookup_login", "confirm_peer_login"]
    )
    val intent: String? = null,
    @field:NotEmpty
    @field:Schema(
        description = "The tools this client supports and has enabled, each as <toolId>@<version> in the one " +
            "version it speaks (ADR-51, docs/03-tool-architektur.md, availability) - e.g. GET /tools/catalog minus " +
            "whatever the user turned off locally. Fixed for this channel's whole lifetime; a candidate list never " +
            "offers a tool outside this set, and activating one directly or in another version fails too. An entry " +
            "without its version, or one tool in two versions, is rejected.",
        example = "[\"ident-fsc@1\", \"enroll-sms@1\", \"auth-sms@1\"]"
    )
    val availableTools: List<String>
)

@Schema(
    description = "An answer to whatever the current step is waiting on instead of a tool run " +
        "(docs/04-orchestrierung.md #3) - e.g. \"accept\"/\"decline\" for the optional device-binding offer of a " +
        "lookup login. Which values are valid depends on what next.step is currently offering."
)
data class AnswerRequest(
    @field:Schema(example = "accept")
    val answer: String
)

@Schema(description = "Raises the channel's durable required-ACR floor; the step-up trigger of the App channel (docs/05-api.md, step-ups).")
data class ChannelPatchRequest(
    @field:Schema(example = "loa3")
    val requiredAcr: String
)
