package com.example.identity.tools.auth_sms.internal

import com.example.identity.contract.tool_api.budget.AttemptBudget
import com.example.identity.contract.tool_api.budget.AttemptBudgets
import com.example.identity.contract.tool_api.values.PhoneNumber
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * At most three TANs per mobile number in ten minutes, whichever tool of this module sends them
 * (ADR-44). Resending is never a wrong guess, so no other counter bounds it. Keyed by the number,
 * because that is what a flood hits. A TAN entered correctly starts the budget over: whoever asked
 * receives the TANs, and someone flooding a stranger never gets there.
 */
@Component
class SmsSendBudget(budgets: AttemptBudgets) : AttemptBudget(budgets, maxPerWindow = 3, window = Duration.ofMinutes(10)) {

    /** Counts one TAN to [phoneNumber]; `false` means do not send. */
    fun trySend(phoneNumber: String): Boolean = tryAttempt(PhoneNumber.normalize(phoneNumber))

    fun received(phoneNumber: String) = reset(PhoneNumber.normalize(phoneNumber))
}
