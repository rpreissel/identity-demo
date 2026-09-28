package com.example.identity.tools.ident_kvnr.internal

import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Self-cleanup by age (docs/07-betrieb.md #3) of `ident_kvnr.ident_tool_session`. */
@Component
class IdKvnrRetentionJob(private val repository: IdentKvnrToolSessionRepository) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        repository.deleteByCreatedAtBefore(cutoff)
    }
}
