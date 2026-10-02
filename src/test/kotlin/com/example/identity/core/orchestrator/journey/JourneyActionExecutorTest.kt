package com.example.identity.core.orchestrator.journey

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.orchestrator.session.ChannelSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * Unit test of the self-lockout guard in [JourneyActionExecutor]: removing a method is refused when
 * the account could no longer reach its channel's floor afterwards. The real policy decides
 * reachability ([StrategyTestFixtures.policy]); accounts and revocation are mocked.
 */
class JourneyActionExecutorTest : BehaviorSpec({

    given("an account with sms (possession) and password (knowledge), on a channel whose floor is loa2") {
        `when`("the holder removes the password, the only knowledge factor") {
            val fixture = SelfLockoutFixture(floor = AcrLevel.LOA2)
            val result = runCatching { fixture.executor.perform(fixture.journey, fixture.channel, Action.RevokeAuthMethod(PASSWORD_INSTANCE)) }

            then("it is refused with 409 - the account would drop below the channel's floor") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.INVALID_STATE_TRANSITION
            }

            then("nothing is revoked") {
                verify(exactly = 0) { fixture.accountDeletionService.revokeMethod(any(), any()) }
            }
        }
    }

    given("an account with sms (possession) and password (knowledge), on a channel whose floor is loa1") {
        `when`("the holder removes the password") {
            val fixture = SelfLockoutFixture(floor = AcrLevel.LOA1)
            fixture.executor.perform(fixture.journey, fixture.channel, Action.RevokeAuthMethod(PASSWORD_INSTANCE))

            then("it is revoked - sms alone still reaches loa1") {
                verify { fixture.accountDeletionService.revokeMethod(ACCOUNT, PASSWORD_INSTANCE) }
            }
        }
    }
})

private val ACCOUNT = AccountId(7L)
private const val PASSWORD_INSTANCE = "password-1"

/** The executor for [ACCOUNT], which holds active sms and password methods; the channel's floor is [floor]. */
private class SelfLockoutFixture(floor: AcrLevel) {
    val channel = ChannelSession(ChannelType.APP, "own-key", TEST_NOW.plusSeconds(3600), now = TEST_NOW).apply {
        state = ChannelState.AUTHENTICATED
        subject = Subject.Account(ACCOUNT)
    }
    val journey = AuthJourney(intent = AuthIntent.MANAGE_AUTH_METHODS, expiresAt = TEST_NOW.plusSeconds(600), createdAt = TEST_NOW)
    private val accountService = mockk<AccountService>(relaxed = true) {
        every { findAccount(ACCOUNT) } returns AccountProfile(
            accountId = ACCOUNT, personId = null,
            authenticationMethods = listOf(method("sms-1", "sms"), method(PASSWORD_INSTANCE, "password"))
        )
        every { claimedTypesOf(ACCOUNT, any()) } returns emptySet()
    }
    val accountDeletionService = mockk<AccountDeletionService>(relaxed = true)
    private val contextFactory = mockk<JourneyContextFactory> { every { acrFloorOf(channel) } returns floor }
    val executor = JourneyActionExecutor(
        journeyRepository = mockk(relaxed = true),
        accountService = accountService,
        identityResolver = mockk(relaxed = true),
        appTokenSessionService = mockk(relaxed = true),
        sessionEvidenceService = mockk(relaxed = true),
        sessionManagementService = mockk(relaxed = true),
        accountDeletionService = accountDeletionService,
        toolRegistry = StrategyTestFixtures.catalog,
        authPolicy = StrategyTestFixtures.policy,
        journeyRecorder = mockk(relaxed = true),
        contextFactory = contextFactory,
    )

    private fun method(id: String, method: String) = AuthMethodView(
        id = id, method = method, active = true, createdAt = TEST_NOW, enrolledUnderAcr = "loa2",
        details = null, enrollmentRef = EnrollmentRef("$method.enrollment", id)
    )
}
