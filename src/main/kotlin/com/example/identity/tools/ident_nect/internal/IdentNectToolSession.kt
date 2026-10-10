package com.example.identity.tools.ident_nect.internal

import java.util.UUID

/**
 * The working data of one ident-nect run, kept through `ToolSessionData`: the Nect case this run
 * waits for, and where Nect sends the user back to. [returnUri] is null for the app channel (`/app/`).
 */
internal data class IdentNectToolSession(
    val caseId: UUID? = null,
    val returnUri: String? = null,
)
