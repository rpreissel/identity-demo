package com.example.identity.core.orchestrator.admin

import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag
import com.example.identity.core.orchestrator.journey.JourneyService
import com.example.identity.core.orchestrator.keycloak.KeycloakRealmSessions
import com.example.identity.core.orchestrator.keycloak.Loa1Login
import com.example.identity.core.orchestrator.keycloak.LoginTheme
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.orchestrator.session.RateLimitRecordRepository
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.session.FeatureFlagService
import com.example.identity.core.orchestrator.tool.ToolAvailabilityService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

data class DemoResetResult(val deletedAccounts: Int, val endedSessions: Int)

/**
 * Puts the demo back to its start, for the admin page and the welcome page alike. Deletion goes
 * through [AccountDeletionService], the same path as the DELETE_ACCOUNT journey, so no method
 * module's credentials stay behind. Every session still live afterwards ends too, also one without
 * an account such as a running registration: back to the start means nobody is left mid-way.
 */
@Service
class DemoReset(
    private val accountService: AccountService,
    private val accountDeletionService: AccountDeletionService,
    private val toolAvailabilityService: ToolAvailabilityService,
    private val featureFlagService: FeatureFlagService,
    private val channelSessionRepository: ChannelSessionRepository,
    private val journeyService: JourneyService,
    private val transactionTemplate: TransactionTemplate,
    private val rateLimitRecordRepository: RateLimitRecordRepository,
    private val loginThemeSwitch: ObjectProvider<LoginThemeSwitch>,
    private val loa1LoginSwitch: ObjectProvider<Loa1LoginSwitch>,
    private val keycloakRealmSessions: ObjectProvider<KeycloakRealmSessions>,
    private val clock: Clock,
) {

    fun reset(): DemoResetResult {
        val liveBefore = channelSessionRepository
            .findByStateNotInAndExpiresAtAfter(TERMINAL_STATES, clock.instant(), Pageable.unpaged()).size
        val accountIds = accountService.allAccountIds()
        accountIds.forEach { accountDeletionService.deleteAccount(it) }
        val liveIds = channelSessionRepository
            .findByStateNotInAndExpiresAtAfter(TERMINAL_STATES, clock.instant(), Pageable.unpaged())
            .map { it.id }
        // One transaction per channel: cancelling the journey saves the channel, so ending it
        // afterwards must see that same managed instance, not the stale one from the query.
        liveIds.forEach { id ->
            transactionTemplate.executeWithoutResult {
                val channel = channelSessionRepository.findByChannelSessionId(id)?.let(LiveChannel::of)
                    ?: return@executeWithoutResult
                journeyService.findActive(id)?.let { journeyService.cancel(it, channel) }
                journeyService.endSession(channel, ChannelState.LOGGED_OUT)
            }
        }
        // Rate limits count per address, account and device; a tester starting over must not hit
        // the limits of the run before.
        rateLimitRecordRepository.deleteAllInBatch()
        // Also sessions of accounts an earlier run left behind: every realm user is an account.
        keycloakRealmSessions.ifAvailable?.logoutAll()
        toolAvailabilityService.applyDefaults()
        featureFlagService.setEnabled(JourneyFeatureFlag.REGISTER_ENROLL_FIRST.key, false)
        loginThemeSwitch.ifAvailable?.switchTo(LoginTheme.FREEMARKER)
        loa1LoginSwitch.ifAvailable?.switchTo(Loa1Login.ORCHESTRATOR)
        return DemoResetResult(deletedAccounts = accountIds.size, endedSessions = liveBefore)
    }

    companion object {
        private val TERMINAL_STATES = ChannelState.entries.filter { it.isTerminal }

        /** What the reset does, for the OpenAPI description of both endpoints. */
        const val DESCRIPTION =
            "Deletes every account, ends every session still live (also one without an account), clears the attempt throttles, ends every Keycloak session, restores the preset tool order and locks per channel, the ident-first registration order, the FreeMarker login theme and the orchestrator's method selection on loa1. " +
                "No account is created again: the test persons register themselves. The person register (/mock-personenverzeichnis) is a foreign system and stays as it is."
    }
}
