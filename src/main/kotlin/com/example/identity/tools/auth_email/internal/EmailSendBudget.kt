package com.example.identity.tools.auth_email.internal

import com.example.identity.contract.tool_api.budget.AttemptBudget
import com.example.identity.contract.tool_api.budget.AttemptBudgets
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * At most three codes per address in ten minutes, whichever tool of this module sends them
 * (ADR-44). Resending is never a wrong guess, so no other counter bounds it. Keyed by the address,
 * because that is what a flood hits. A code entered correctly starts the budget over: whoever
 * asked receives the codes, and someone flooding a stranger never gets there.
 */
@Component
class EmailSendBudget(budgets: AttemptBudgets) : AttemptBudget(budgets, maxPerWindow = 3, window = Duration.ofMinutes(10)) {

    /** Counts one code to [email]; `false` means do not send. */
    fun trySend(email: String): Boolean = tryAttempt(normalize(email))

    fun received(email: String) = reset(normalize(email))

    // As `tool_api.Email` normalizes; not Email itself, since a stored address is already valid.
    private fun normalize(email: String) = email.trim().lowercase()
}
