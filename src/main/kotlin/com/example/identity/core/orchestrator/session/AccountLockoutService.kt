package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.SignInLog

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.OrchestratorException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * Account-level lockout after failed AUTH attempts, with two entry points. An IDENTIFIED_AUTH tool
 * knows its account, so [assertNotLocked] answers with an explicit 423. A LOOKUP_AUTH tool resolves
 * the account from submitted input; a visible lockout would reveal that the account exists, so it
 * asks [isLocked] and folds the lock into its ordinary failure. Both key on the resolved account,
 * since `channel.accountId` stays null in a lookup login until a proof succeeds.
 */
@Service
@Transactional
class AccountLockoutService(
    private val counter: AttemptCounter,
    private val signInLog: SignInLog,
    private val clock: Clock
) {

    fun isLocked(accountId: Long): Boolean = counter.isLocked(ThrottleScope.ACCOUNT, key(accountId))

    fun assertNotLocked(accountId: Long) {
        if (isLocked(accountId)) {
            throw OrchestratorException.accountLocked(
                Text("Zu viele fehlgeschlagene Anmeldeversuche fuer diesen Account - bitte spaeter erneut versuchen")
            )
        }
    }

    /**
     * One failed proof: counted and written to the sign-in log, with the lockout if this failure
     * tripped it (ADR-39). Every such failure passes here, whatever the channel.
     */
    fun recordFailure(accountId: Long, channel: String?, method: String) {
        val lockedBefore = counter.lockedUntil(ThrottleScope.ACCOUNT, key(accountId))
        counter.recordFailure(ThrottleScope.ACCOUNT, key(accountId), MAX_FAILURES, LOCKOUT_DURATION)
        signInLog.signInFailed(accountId, channel, method)
        val lockedNow = counter.lockedUntil(ThrottleScope.ACCOUNT, key(accountId))
        if (lockedNow != null && lockedNow != lockedBefore && clock.instant().isBefore(lockedNow)) {
            signInLog.lockedOut(accountId, channel, lockedNow)
        }
    }

    /** Resets the throttle on every successful AUTH completion. */
    fun recordSuccess(accountId: Long) = counter.reset(ThrottleScope.ACCOUNT, key(accountId))

    private fun key(accountId: Long) = accountId.toString()

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
    }
}
