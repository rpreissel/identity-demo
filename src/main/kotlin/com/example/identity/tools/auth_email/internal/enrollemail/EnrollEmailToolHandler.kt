package com.example.identity.tools.auth_email.internal.enrollemail

import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.directory.EMAIL_ANCHOR_ENROLLMENT
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=enroll-email: turns the account's confirmed address into an authentication method. A
 * one-shot, because `confirm-email` already proved control; the descriptor's `requires` guarantees
 * the anchor exists. The credential is that anchor ([EMAIL_ANCHOR_ENROLLMENT]), so this module
 * owns no enrollment table.
 */
@Component
class EnrollEmailToolHandler(
    private val sessions: ToolSessionData,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, EnrollEmailToolSession())
        return completed()
    }

    /**
     * A re-read after completion returns the same outcome: the tool has exactly one state, so
     * there is no step to describe and nothing a client could still submit.
     */
    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.require<EnrollEmailToolSession>(toolSessionId)
        return completed()
    }

    // No amr: nothing was proven in this run, so it must not raise the channel's assurance.
    private fun completed() = ToolOutcome.Completed.Enrolled(
        enrollmentRef = EMAIL_ANCHOR_ENROLLMENT,
        amr = emptyList(),
    )
}
