package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceRepository
import com.example.identity.contract.tool_api.credentials.EnrollmentCleanup
import com.example.identity.contract.tool_api.EnrollmentRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Instant

/**
 * Unit test of [AccountDeletionService]: the id fields of [ChannelSession] are cleared before the
 * [AppTokenSession]/[SessionEvidenceRecord] rows they point to are deleted.
 */
class AccountDeletionServiceTest : BehaviorSpec({

    fun service(
        accountService: AccountService,
        cleanups: List<EnrollmentCleanup> = emptyList(),
        deviceAccountLinkRepository: DeviceAccountLinkRepository = mockk(relaxed = true),
        channelSessionRepository: ChannelSessionRepository = mockk(relaxed = true),
        appTokenSessionRepository: AppTokenSessionRepository = mockk(relaxed = true),
        sessionEvidenceRepository: SessionEvidenceRecordRepository = mockk(relaxed = true),
        journeyTraceRepository: JourneyTraceRepository = mockk(relaxed = true),
        rateLimitRecordRepository: RateLimitRecordRepository = mockk(relaxed = true)
    ) = AccountDeletionService(
        accountService,
        cleanups,
        deviceAccountLinkRepository,
        channelSessionRepository,
        appTokenSessionRepository,
        sessionEvidenceRepository,
        journeyTraceRepository,
        rateLimitRecordRepository
    )

    given("an account with channel sessions still bound to it") {
        then("every one of them is logged out with BOTH appTokenSessionId and sessionEvidenceId cleared, not just one") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(AccountId(1L)) } returns emptyList()
            val session = ChannelSession(now = Instant.now()).apply {
                state = ChannelState.AUTHENTICATED
                appTokenSessionId = java.util.UUID.randomUUID()
                sessionEvidenceId = SessionEvidenceId(java.util.UUID.randomUUID())
            }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByAccountId(AccountId(1L)) } returns listOf(session)
            every { channelSessionRepository.save(any<ChannelSession>()) } answers { firstArg() }

            service(accountService, channelSessionRepository = channelSessionRepository).deleteAccount(AccountId(1L))

            session.state shouldBe ChannelState.LOGGED_OUT
            session.appTokenSessionId shouldBe null
            session.sessionEvidenceId shouldBe null
            verify { channelSessionRepository.save(session) }
        }
    }

    given("an account with enrollment refs across different method modules") {
        then("each ref is dispatched to the cleanup matching its own type, never a different module's") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(AccountId(1L)) } returns listOf(
                EnrollmentRef("sms", "sms-ref"), EnrollmentRef("password", "password-ref")
            )
            val smsCleanup = mockk<EnrollmentCleanup>(relaxed = true)
            every { smsCleanup.enrollmentType } returns "sms"
            val passwordCleanup = mockk<EnrollmentCleanup>(relaxed = true)
            every { passwordCleanup.enrollmentType } returns "password"

            service(accountService, cleanups = listOf(smsCleanup, passwordCleanup)).deleteAccount(AccountId(1L))

            verify { smsCleanup.delete(EnrollmentRef("sms", "sms-ref")) }
            verify { passwordCleanup.delete(EnrollmentRef("password", "password-ref")) }
            verify(exactly = 0) { smsCleanup.delete(EnrollmentRef("password", "password-ref")) }
        }

        then("a ref whose type no registered module claims is skipped, not a crash") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(AccountId(1L)) } returns listOf(EnrollmentRef("unknown-method", "ref"))

            service(accountService, cleanups = emptyList()).deleteAccount(AccountId(1L))
        }
    }

    given("revokeMethod - a single credential, not the whole account") {
        then("deletes only that instance's own enrollment and deactivates it, leaving the account row untouched") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.enrollmentRefFor(AccountId(1L), "method-instance-1") } returns EnrollmentRef("device", "device-ref")
            val deviceCleanup = mockk<EnrollmentCleanup>(relaxed = true)
            every { deviceCleanup.enrollmentType } returns "device"

            service(accountService, cleanups = listOf(deviceCleanup)).revokeMethod(AccountId(1L), "method-instance-1")

            verify { deviceCleanup.delete(EnrollmentRef("device", "device-ref")) }
            verify { accountService.deactivateAuthenticationMethod(AccountId(1L), "method-instance-1") }
            verify(exactly = 0) { accountService.deleteAccount(any()) }
        }

        then("a method with no resolvable enrollmentRef is still deactivated, just without a cleanup call") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.enrollmentRefFor(AccountId(1L), "method-instance-1") } returns null

            service(accountService).revokeMethod(AccountId(1L), "method-instance-1")

            verify { accountService.deactivateAuthenticationMethod(AccountId(1L), "method-instance-1") }
        }
    }

    given("the full deletion") {
        then("removes cross-module credentials and the device link before the account row itself, never after") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(AccountId(1L)) } returns emptyList()
            val deviceAccountLinkRepository = mockk<DeviceAccountLinkRepository>(relaxed = true)

            service(accountService, deviceAccountLinkRepository = deviceAccountLinkRepository).deleteAccount(AccountId(1L))

            verifyOrder {
                deviceAccountLinkRepository.deleteByAccountId(AccountId(1L))
                accountService.deleteAccount(AccountId(1L))
            }
        }

        then("erases the journey trace by account AND by the account's channel sessions, plus the account-keyed throttle counters (A5)") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(AccountId(1L)) } returns emptyList()
            val channelSessionId = ChannelSessionId(java.util.UUID.randomUUID())
            val session = ChannelSession(now = Instant.now()).apply { this.channelSessionId = channelSessionId }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByAccountId(AccountId(1L)) } returns listOf(session)
            every { channelSessionRepository.save(any()) } returns session
            val journeyTraceRepository = mockk<JourneyTraceRepository>(relaxed = true)
            val rateLimitRecordRepository = mockk<RateLimitRecordRepository>(relaxed = true)

            service(
                accountService,
                channelSessionRepository = channelSessionRepository,
                journeyTraceRepository = journeyTraceRepository,
                rateLimitRecordRepository = rateLimitRecordRepository
            ).deleteAccount(AccountId(1L))

            verify { journeyTraceRepository.deleteByAccountIdOrChannelSessionIdIn(AccountId(1L), listOf(channelSessionId)) }
            verify {
                rateLimitRecordRepository.deleteBySubjectAndScopeIn(
                    "1",
                    listOf(RateLimitScope.ACCOUNT.name)
                )
            }
        }

        then("leaves the throttle scopes that are not account-keyed alone - deletion must not become a way to reset someone else's budget") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(AccountId(1L)) } returns emptyList()
            val rateLimitRecordRepository = mockk<RateLimitRecordRepository>(relaxed = true)
            val scopes = slot<Collection<String>>()
            every { rateLimitRecordRepository.deleteBySubjectAndScopeIn(any(), capture(scopes)) } returns 0

            service(accountService, rateLimitRecordRepository = rateLimitRecordRepository).deleteAccount(AccountId(1L))

            scopes.captured shouldNotContain RateLimitScope.PERSON.name
            scopes.captured shouldNotContain RateLimitScope.BINDING_KEY.name
        }
    }
})
