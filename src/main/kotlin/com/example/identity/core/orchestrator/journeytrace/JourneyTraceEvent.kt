package com.example.identity.core.orchestrator.journeytrace

/**
 * What one journey-trace line records. Stored and sent by name; the operator's log view words
 * each one (frontend `journeyTraceText.ts`).
 */
enum class JourneyTraceEvent {
    // What a journey decided on (its `JourneyEvent`s plus a finished tool run), logged with the decision.
    STARTED,
    ANSWERED,
    ACTION_COMPLETED,
    TOOL_COMPLETED,
    TOOL_ABANDONED,
    OFFER_EXHAUSTED,
    SUB_JOURNEY_FINISHED,
    SUB_JOURNEY_CANCELLED,

    // What happened around a decision.
    /** Evidence carried over from the channel's Keycloak session, before the journey's first decision (ADR-59). */
    EVIDENCE_TAKEN_OVER,
    TOOL_ACTIVATED,
    /** A failed attempt; charged to the journey's attempt budget. */
    TOOL_FAILED,
    BACK_TO_SELECTION,
    INVITATION_REFUSED,

    // How it ended.
    JOURNEY_CANCELLED,
    LOGGED_OUT,
    /** Channel-level: the channel ended with its Keycloak session. */
    CHANNEL_EXPIRED
}
