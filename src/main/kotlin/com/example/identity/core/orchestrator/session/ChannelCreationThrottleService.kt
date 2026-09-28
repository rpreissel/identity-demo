package com.example.identity.core.orchestrator.session

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.OrchestratorException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Rate limit on channel creation per DPoP binding key. `AuthJourney.attemptBudget` bounds guesses
 * within one journey, but a fresh journey costs one free `POST .../app/channels`. A binding key
 * costs nothing to rotate, so this is a speed bump; the real bounds are [AccountLockoutService] and
 * [PersonLockoutService]. Rolling window, answered with 429 rather than a lock.
 */
@Service
@Transactional
class ChannelCreationThrottleService(private val counter: AttemptCounter) {

    fun recordAndAssertWithinBudget(bindingKeyRef: String) {
        val withinBudget = counter.recordWindowedAttempt(
            ThrottleScope.BINDING_KEY, bindingKeyRef, MAX_PER_WINDOW, WINDOW
        )
        if (!withinBudget) {
            throw OrchestratorException.tooManyRequests(
                Text("Zu viele Kanaleroeffnungen fuer dieses Geraet - bitte spaeter erneut versuchen")
            )
        }
    }

    companion object {
        private const val MAX_PER_WINDOW = 20
        private val WINDOW: Duration = Duration.ofMinutes(5)
    }
}
