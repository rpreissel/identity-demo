package com.example.identity.core.orchestrator.session

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.credentials.EnrollmentCleanup
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.UUID

/**
 * Unit test of [AccountDeletionService]: the id fields of [ChannelSession] are cleared before the
 * [AppTokenSession]/[SessionEvidenceRecord] rows they point to are deleted, credentials go through
 * the cleanup of their own module, and only account-keyed throttle counters are erased.
 */
class AccountDeletionServiceTest : BehaviorSpec({

    val accountId = AccountId(1L)

    given("an account with an authenticated channel session still bound to it") {
        val fixture = AccountDeletionFixture()
        val session = ChannelSession(now = TEST_NOW).apply {
            channelSessionId = ChannelSessionId(UUID.randomUUID())
            state = ChannelState.AUTHENTICATED
            appTokenSessionId = UUID.randomUUID()
            sessionEvidenceId = SessionEvidenceId(UUID.randomUUID())
        }
        every { fixture.channelSessionRepository.findByAccountId(accountId) } returns listOf(session)
        every { fixture.channelSessionRepository.save(any<ChannelSession>()) } answers { firstArg() }

        `when`("the account is deleted") {
            fixture.service.deleteAccount(accountId)

            then("the session is logged out with BOTH appTokenSessionId and sessionEvidenceId cleared, not just one") {
                session.state shouldBe ChannelState.LOGGED_OUT
                session.appTokenSessionId.shouldBeNull()
                session.sessionEvidenceId.shouldBeNull()
                verify { fixture.channelSessionRepository.save(session) }
            }

            then("the journey trace is erased by account AND by the account's channel sessions") {
                verify { fixture.journeyTraceRepository.deleteByAccountIdOrChannelSessionIdIn(accountId, listOf(session.channelSessionId!!)) }
            }

            then("the device link goes before the account row itself, never after") {
                verifyOrder {
                    fixture.deviceAccountLinkRepository.deleteByAccountId(accountId)
                    fixture.accountService.deleteAccount(accountId)
                }
            }
        }
    }

    given("an account with throttle counters") {
        val fixture = AccountDeletionFixture()
        val scopes = slot<Collection<String>>()
        every { fixture.rateLimitRecordRepository.deleteBySubjectAndScopeIn("1", capture(scopes)) } returns 0

        `when`("the account is deleted") {
            fixture.service.deleteAccount(accountId)

            then("the account-keyed counters are erased (A5)") {
                scopes.captured shouldBe listOf(RateLimitScope.ACCOUNT.name)
            }

            then("the scopes that are not account-keyed stay - deletion must not reset someone else's budget") {
                scopes.captured shouldNotContain RateLimitScope.PERSON.name
                scopes.captured shouldNotContain RateLimitScope.BINDING_KEY.name
            }
        }
    }

    given("an account with enrollment refs across different method modules") {
        val fixture = AccountDeletionFixture(cleanupTypes = listOf("sms", "password"))
        every { fixture.accountService.allEnrollmentRefs(accountId) } returns listOf(
            EnrollmentRef("sms", "sms-ref"), EnrollmentRef("password", "password-ref")
        )

        `when`("the account is deleted") {
            fixture.service.deleteAccount(accountId)

            then("each ref is dispatched to the cleanup matching its own type, never a different module's") {
                verify { fixture.cleanup("sms").delete(EnrollmentRef("sms", "sms-ref")) }
                verify { fixture.cleanup("password").delete(EnrollmentRef("password", "password-ref")) }
                verify(exactly = 0) { fixture.cleanup("sms").delete(EnrollmentRef("password", "password-ref")) }
            }
        }
    }

    given("an account with an enrollment ref whose type no registered module claims") {
        val fixture = AccountDeletionFixture()
        every { fixture.accountService.allEnrollmentRefs(accountId) } returns listOf(EnrollmentRef("unknown-method", "ref"))

        `when`("the account is deleted") {
            fixture.service.deleteAccount(accountId)

            then("the ref is skipped and the account row still goes") {
                verify { fixture.accountService.deleteAccount(accountId) }
            }
        }
    }

    given("a method instance with a device enrollment") {
        val fixture = AccountDeletionFixture(cleanupTypes = listOf("device"))
        every { fixture.accountService.enrollmentRefFor(accountId, "method-instance-1") } returns EnrollmentRef("device", "device-ref")

        `when`("revoking that single method") {
            fixture.service.revokeMethod(accountId, "method-instance-1")

            then("only that instance's own enrollment is deleted and the instance deactivated, the account row stays") {
                verify { fixture.cleanup("device").delete(EnrollmentRef("device", "device-ref")) }
                verify { fixture.accountService.deactivateAuthenticationMethod(accountId, "method-instance-1") }
                verify(exactly = 0) { fixture.accountService.deleteAccount(any()) }
            }
        }
    }

    given("a method instance without a resolvable enrollment ref") {
        val fixture = AccountDeletionFixture()
        every { fixture.accountService.enrollmentRefFor(accountId, "method-instance-1") } returns null

        `when`("revoking that method") {
            fixture.service.revokeMethod(accountId, "method-instance-1")

            then("it is still deactivated, just without a cleanup call") {
                verify { fixture.accountService.deactivateAuthenticationMethod(accountId, "method-instance-1") }
            }
        }
    }
})

/** The service over relaxed mocks; [cleanupTypes] each get a registered [EnrollmentCleanup]. */
private class AccountDeletionFixture(cleanupTypes: List<String> = emptyList()) {
    val accountService = mockk<AccountService>(relaxed = true)
    private val cleanups = cleanupTypes.associateWith { type ->
        mockk<EnrollmentCleanup>(relaxed = true) { every { enrollmentType } returns type }
    }
    val deviceAccountLinkRepository = mockk<DeviceAccountLinkRepository>(relaxed = true)
    val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
    val journeyTraceRepository = mockk<JourneyTraceRepository>(relaxed = true)
    val rateLimitRecordRepository = mockk<RateLimitRecordRepository>(relaxed = true)
    val service = AccountDeletionService(
        accountService,
        cleanups.values.toList(),
        deviceAccountLinkRepository,
        channelSessionRepository,
        mockk<AppTokenSessionRepository>(relaxed = true),
        mockk<SessionEvidenceRecordRepository>(relaxed = true),
        mockk<KeycloakSessionEvidenceRepository>(relaxed = true),
        journeyTraceRepository,
        rateLimitRecordRepository
    )

    init {
        every { accountService.allEnrollmentRefs(any()) } returns emptyList()
        every { accountService.isEnrollmentSharedWithOtherAccount(any(), any()) } returns false
    }

    fun cleanup(type: String): EnrollmentCleanup = cleanups.getValue(type)
}
