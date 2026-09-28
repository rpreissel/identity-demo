package com.example.identity.contract.tool_api.retention

import java.time.Instant

/**
 * One module's deletion of its own tool-session tables, triggered by the orchestrator's
 * `ToolSessionRetentionDriver`. Only schedule and cutoff are shared. The module decides what it
 * deletes, because only it knows which tables are session-scoped (an `enrollment` row must
 * survive). Implementations are `@Transactional` on their own.
 */
fun interface ToolSessionSweeper {
    /** Delete this module's tool-session rows created before [cutoff]. */
    fun sweep(cutoff: Instant)
}

