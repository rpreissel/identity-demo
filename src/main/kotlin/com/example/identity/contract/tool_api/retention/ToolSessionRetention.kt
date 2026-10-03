package com.example.identity.contract.tool_api.retention

import java.time.Instant

/**
 * One module's deletion of short-lived data it keeps outside a tool session (today the QR login
 * requests), triggered by the orchestrator's `ToolSessionRetentionDriver`. A tool's working data
 * needs none: it lives with its tool session and ends with it (`ToolSessionData`, ADR-49). Only
 * schedule and cutoff are shared; the module decides what it deletes, because only it knows which
 * rows are short-lived (an `enrollment` row must survive). Implementations are `@Transactional`.
 */
fun interface ToolSessionSweeper {
    /** Delete this module's short-lived rows that ended before [cutoff]. */
    fun sweep(cutoff: Instant)
}

