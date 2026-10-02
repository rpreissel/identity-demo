package com.example.identity.tools.auth_password.api.v1

import com.example.identity.TEST_CLOCK
import com.example.identity.tools.auth_password.PASSWORD_EXISTS
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.InvalidStateException
import com.example.identity.contract.tool_api.KeycloakToolCalls
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.tools.auth_password.PASSWORD_EXISTS_MARKER
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.tools.auth_password.internal.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.tools.auth_password.PasswordModule
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollment
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.identity.tools.auth_password.internal.PasswordCredentialPortImpl
import com.example.identity.tools.auth_password.internal.PasswordHasher
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional

private const val CURRENT_PASSWORD = "correct-horse-battery"
private const val NEW_PASSWORD = "brand-new-secret"

/**
 * Unit test of [MgmtPasswordController]'s branches for Keycloak's native password credential: the
 * real [PasswordCredentialPortImpl] over an in-memory enrollment store, the booking
 * ([KeycloakToolCalls]) and the lockout mocked. That the caller is Keycloak for the path's account
 * is `KeycloakToolCallsServiceTest`'s.
 */
class MgmtPasswordControllerTest : BehaviorSpec({

    val accountId = AccountId(7L)

    given("an account with an enrolled password") {
        `when`("the correct password is verified") {
            val fixture = MgmtPasswordFixture(accountId, enrolled = true)
            val response = fixture.controller.verify(accountId, "kc:7", MgmtPasswordVerifyRequest(CURRENT_PASSWORD))

            then("it is valid, and booked as a proof") {
                response.body!!.valid shouldBe true
                fixture.booked.captured.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
            }
        }

        `when`("a wrong password is verified") {
            val fixture = MgmtPasswordFixture(accountId, enrolled = true)
            val response = fixture.controller.verify(accountId, "kc:7", MgmtPasswordVerifyRequest("wrong-password"))

            then("it is invalid, and booked as a failed attempt") {
                response.body!!.valid shouldBe false
                fixture.booked.captured.shouldBeInstanceOf<ToolOutcome.Failed.KnownAccountAuth>()
            }
        }

        `when`("Keycloak's 'reset password' sets a new one") {
            val fixture = MgmtPasswordFixture(accountId, enrolled = true)
            fixture.controller.set(accountId, "kc:7", MgmtPasswordSetRequest(NEW_PASSWORD))

            then("a new instance is booked, carrying its own 'has a password' claim like one set up in the app") {
                val enrolled = fixture.booked.captured.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                enrolled.enrollmentRef.type shouldBe PASSWORD_ENROLLMENT_TYPE
                enrolled.claims.single().attributeType shouldBe PASSWORD_EXISTS
                enrolled.claims.single().value shouldBe PASSWORD_EXISTS_MARKER
                verify { fixture.keycloakToolCalls.apply(accountId, PasswordModule, any()) }
            }
        }
    }

    given("an account with an enrolled password that is locked after failed attempts") {
        `when`("the correct password is verified") {
            val fixture = MgmtPasswordFixture(accountId, enrolled = true, locked = true)
            val response = fixture.controller.verify(accountId, "kc:7", MgmtPasswordVerifyRequest(CURRENT_PASSWORD))

            then("even the correct password is refused until the lock expires - the app's lockout, shared") {
                response.body!!.valid shouldBe false
            }

            then("nothing is booked, so the attempt neither counts nor resets the lock") {
                verify(exactly = 0) { fixture.keycloakToolCalls.apply(any(), any(), any()) }
            }
        }
    }

    given("an account with no password enrolled yet") {
        `when`("a password is verified against it") {
            val fixture = MgmtPasswordFixture(accountId, enrolled = false)
            val response = fixture.controller.verify(accountId, "kc:7", MgmtPasswordVerifyRequest("anything"))

            then("it is invalid without throwing (constant shape, no enumeration oracle)") {
                response.body!!.valid shouldBe false
                verify { fixture.keycloakToolCalls.apply(accountId, PasswordModule, any()) }
            }
        }

        `when`("'reset password' tries to give it a password it never had") {
            val fixture = MgmtPasswordFixture(accountId, enrolled = false)
            val result = runCatching { fixture.controller.set(accountId, "kc:7", MgmtPasswordSetRequest(NEW_PASSWORD)) }

            then("it is refused - set only replaces, enroll-password is the way to add one") {
                shouldThrow<InvalidStateException> { result.getOrThrow() }
                verify(exactly = 0) { fixture.enrollmentRepository.save(any()) }
                verify(exactly = 0) { fixture.keycloakToolCalls.apply(any(), any(), any()) }
            }
        }
    }
})

/**
 * The controller for [accountId]: with [enrolled], the account's active password is [CURRENT_PASSWORD]
 * (enrollment 1); [locked] is the shared lockout's answer. Every booking is captured in [booked].
 */
private class MgmtPasswordFixture(accountId: AccountId, enrolled: Boolean, locked: Boolean = false) {
    private val enrollmentRef = EnrollmentRef(PASSWORD_ENROLLMENT_TYPE, "1")
    val booked = slot<ToolOutcome>()
    val keycloakToolCalls = mockk<KeycloakToolCalls> {
        every { requireKeycloakFor(accountId, any()) } returns Unit
        every { apply(accountId, any(), capture(booked)) } returns Unit
    }
    private val lockouts = mockk<Lockouts> { every { isLockedOut(accountId) } returns locked }
    private val accountDirectory = mockk<AccountDirectory> {
        every { activeEnrollment(accountId, PasswordModule.method) } returns enrollmentRef.takeIf { enrolled }
    }
    val enrollmentRepository = mockk<AuthPasswordEnrollmentRepository> {
        every { findById(1L) } returns Optional.of(
            AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(CURRENT_PASSWORD), createdAt = TEST_NOW).apply { id = 1L }
        )
        every { save(any<AuthPasswordEnrollment>()) } answers { firstArg<AuthPasswordEnrollment>().apply { id = 2L } }
    }
    val controller = MgmtPasswordController(keycloakToolCalls, lockouts, accountDirectory, PasswordCredentialPortImpl(enrollmentRepository, TEST_CLOCK))
}
