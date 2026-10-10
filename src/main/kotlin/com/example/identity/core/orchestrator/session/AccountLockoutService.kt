package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.SignInLog

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.OrchestratorException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * Account-level lockout after failed AUTH attempts. An attempt is booked before the secret is checked
 * ([admitAttempt]), so parallel attempts cannot all pass the check before the fifth counts
 * (docs/07-betrieb.md #4). A KNOWN_ACCOUNT_AUTH tool knows its account and gets an explicit 423. An
 * ACCOUNT_LOOKUP_AUTH tool folds a refused attempt into its ordinary failure, since a visible lock
 * would reveal that the account exists. Both key on the resolved account.
 */
@Service
@Transactional
class AccountLockoutService(
    private val counter: RateLimitCounter,
    private val signInLog: SignInLog,
    private val clock: Clock
) {

    fun isLocked(accountId: AccountId): Boolean = counter.isLocked(RateLimitScope.ACCOUNT, key(accountId))

    fun assertNotLocked(accountId: AccountId) {
        if (isLocked(accountId)) throw locked()
    }

    private fun locked() = OrchestratorException.accountLocked(
        Text("Zu viele fehlgeschlagene Anmeldeversuche fuer diesen Account - bitte spaeter erneut versuchen")
    )

    /**
     * Books one attempt before its secret is checked; false if the account is locked, and then
     * nothing is booked. The booked attempt counts as a failure until [recordSuccess] or [refund].
     */
    fun admitAttempt(accountId: AccountId): Boolean =
        counter.admitAttempt(RateLimitScope.ACCOUNT, key(accountId), MAX_FAILURES, LOCKOUT_DURATION)

    /** [admitAttempt], answered with the explicit 423 a KNOWN_ACCOUNT_AUTH tool may show. */
    fun requireAttempt(accountId: AccountId) {
        if (!admitAttempt(accountId)) throw locked()
    }

    /** Takes back a booked attempt that checked no secret, such as a resent code. */
    fun refund(accountId: AccountId) = counter.refundAttempt(RateLimitScope.ACCOUNT, key(accountId), MAX_FAILURES)

    /**
     * One failed proof of a booked attempt: written to the sign-in log, with the lockout if the
     * account is locked now (ADR-39). Counted already, at [admitAttempt]. [tool]: the tool in the
     * version the client spoke.
     */
    fun recordFailure(accountId: AccountId, channel: String?, method: String, tool: String?) {
        signInLog.signInFailed(accountId, channel, method, tool)
        val lockedNow = counter.lockedUntil(RateLimitScope.ACCOUNT, key(accountId))
        if (lockedNow != null && clock.instant().isBefore(lockedNow)) {
            signInLog.lockedOut(accountId, channel, lockedNow)
        }
    }

    /** Resets the rate limit on every successful AUTH completion. */
    fun recordSuccess(accountId: AccountId) = counter.reset(RateLimitScope.ACCOUNT, key(accountId))

    private fun key(accountId: AccountId) = accountId.toString()

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
    }
}
