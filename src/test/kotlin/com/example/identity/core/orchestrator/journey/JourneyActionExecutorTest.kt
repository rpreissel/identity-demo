package com.example.identity.core.orchestrator.journey

import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
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
import com.example.identity.core.orchestrator.session.SessionEvidenceService
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import java.util.UUID
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * Unit test of the guards in [JourneyActionExecutor]: removing a method is refused when the account
 * could no longer reach its channel's floor afterwards, and a deletion re-checks its level against
 * the current evidence. The real policy decides ([StrategyTestFixtures.policy]); accounts,
 * revocation and deletion are mocked.
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
    given("an identified account with sms and password, whose session only proved sms (loa1)") {
        `when`("the account deletion is executed") {
            val fixture = DeleteAccountFixture(provenAmr = listOf("sms"))
            val result = runCatching { fixture.executor.perform(fixture.journey, fixture.channel, Action.DeleteAccount) }

            then("it is refused - requiredAcr(account) is loa2 and is re-checked right before deleting") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldContain "without satisfying loa2"
            }

            then("nothing is deleted") {
                verify(exactly = 0) { fixture.accountDeletionService.deleteAccount(any()) }
            }
        }
    }

    given("an identified account with sms and password, whose session proved both (loa2)") {
        `when`("the account deletion is executed") {
            val fixture = DeleteAccountFixture(provenAmr = listOf("sms", "password"))
            fixture.executor.perform(fixture.journey, fixture.channel, Action.DeleteAccount)

            then("the account is deleted") {
                verify { fixture.accountDeletionService.deleteAccount(ACCOUNT) }
            }
        }
    }

    given("a confirming session and a peer-approval tool that reports amr, level and factors anyway") {
        `when`("the approval is recorded") {
            val fixture = RecordApprovalFixture()
            val outcome = ToolOutcome.Completed.Approved(
                amr = listOf("qr"), achievedAcr = AcrLevel.LOA3, factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            )
            fixture.executor.perform(fixture.journey, fixture.channel, Action.RecordApproval(tool("approve-qr"), outcome))

            then("the confirming session's evidence is left unchanged") {
                verify(exactly = 0) { fixture.sessionEvidenceService.applyEvidence(any(), any()) }
                verify(exactly = 0) { fixture.sessionEvidenceService.attachToChannel(any(), any()) }
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
        boundKeyRef = null, reference = null, enrollmentRef = EnrollmentRef("$method.enrollment", id)
    )
}

/** An authenticated channel of [ACCOUNT] (identified, sms and password at loa2) whose evidence holds [provenAmr]. */
private class DeleteAccountFixture(provenAmr: List<String>) {
    private val account = StrategyTestFixtures.account(
        StrategyTestFixtures.method("sms", AcrLevel.LOA2), StrategyTestFixtures.method("password", AcrLevel.LOA2),
        accountId = ACCOUNT
    )
    val channel = authenticatedChannel()
    val journey = AuthJourney(intent = AuthIntent.DELETE_ACCOUNT, expiresAt = TEST_NOW.plusSeconds(600), createdAt = TEST_NOW)
    private val factors = mapOf("sms" to FactorType.POSSESSION, "password" to FactorType.KNOWLEDGE)
    private val contextFactory = mockk<JourneyContextFactory> {
        every { contextFor(journey, channel) } returns StrategyTestFixtures.ctx(
            account = account,
            evidence = StrategyTestFixtures.evidence(provenAmr, provenAmr.map { factors.getValue(it) }.toSet(), account = account)
        )
    }
    val accountDeletionService = mockk<AccountDeletionService>(relaxed = true)
    val executor = executor(accountDeletionService = accountDeletionService, contextFactory = contextFactory)
}

/** The executor with a real [JourneyRecorder], so what reaches the session's evidence is observable. */
private class RecordApprovalFixture {
    val channel = authenticatedChannel().apply { sessionEvidenceId = SessionEvidenceId(UUID.randomUUID()) }
    val journey = AuthJourney(intent = AuthIntent.CONFIRM_PEER_LOGIN, expiresAt = TEST_NOW.plusSeconds(600), createdAt = TEST_NOW)
    val sessionEvidenceService = mockk<SessionEvidenceService>(relaxed = true)
    val executor = executor(
        sessionEvidenceService = sessionEvidenceService,
        journeyRecorder = JourneyRecorder(sessionEvidenceService, mockk(relaxed = true), mockk(relaxed = true))
    )
}

private fun authenticatedChannel() = ChannelSession(ChannelType.APP, "own-key", TEST_NOW.plusSeconds(3600), now = TEST_NOW).apply {
    state = ChannelState.AUTHENTICATED
    subject = Subject.Account(ACCOUNT)
}

private fun executor(
    accountDeletionService: AccountDeletionService = mockk(relaxed = true),
    sessionEvidenceService: SessionEvidenceService = mockk(relaxed = true),
    journeyRecorder: JourneyRecorder = mockk(relaxed = true),
    contextFactory: JourneyContextFactory = mockk(relaxed = true),
) = JourneyActionExecutor(
    journeyRepository = mockk(relaxed = true),
    accountService = mockk(relaxed = true),
    identityResolver = mockk(relaxed = true),
    appTokenSessionService = mockk(relaxed = true),
    sessionEvidenceService = sessionEvidenceService,
    sessionManagementService = mockk(relaxed = true),
    accountDeletionService = accountDeletionService,
    toolRegistry = StrategyTestFixtures.catalog,
    authPolicy = StrategyTestFixtures.policy,
    journeyRecorder = journeyRecorder,
    contextFactory = contextFactory,
)
