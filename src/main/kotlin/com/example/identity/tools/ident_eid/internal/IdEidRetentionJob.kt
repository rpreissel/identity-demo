package com.example.identity.tools.ident_eid.internal

import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Self-cleanup by age (docs/07-betrieb.md #3) of the tool-session-scoped working data. */
@Component
class IdEidRetentionJob(private val repository: IdentEidToolSessionRepository) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        repository.deleteByCreatedAtBefore(cutoff)
    }

}
