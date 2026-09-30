package com.example.identity.core.orchestrator.domain

import java.util.UUID

/** The id of an auth journey. Entities and JSON keep the bare UUID. */
@JvmInline
value class JourneyId(val value: UUID) {
    override fun toString(): String = value.toString()
}

/** The id of the session evidence a channel collects. Entities and JSON keep the bare UUID. */
@JvmInline
value class SessionEvidenceId(val value: UUID) {
    override fun toString(): String = value.toString()
}
