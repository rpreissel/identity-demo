package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.PASSWORD_EXISTS_MARKER
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.kc.PeerAuthValidationException
import com.example.identity.core.orchestrator.session.AccountLockoutService
import com.example.identity.tools.auth_password.AuthPasswordDescriptor
import com.example.identity.tools.auth_password.AuthPasswordLookupDescriptor
import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID

/**
 * Unit test of [KeycloakToolCallsService] with the lockout and account services mocked. Checks the
 * peer-auth binding check and how each outcome of a channel-less tool call is booked, including
 * that an enrollment carries no `enrolledUnderAcr` and its claims point at the new instance.
 */
class KeycloakToolCallsServiceTest : BehaviorSpec({

    val accountId = 7L

    given("requireKeycloakFor()") {
        val service = KeycloakToolCallsService(mockk(), mockk())

        `when`("the binding is Keycloak's for this account") {
            val result = runCatching { service.requireKeycloakFor(accountId, "kc:7") }

            then("it passes") {
                result.isSuccess shouldBe true
            }
        }

        `when`("the binding is Keycloak's for another account") {
            val result = runCatching { service.requireKeycloakFor(accountId, "kc:8") }

            then("it refuses with PeerAuthValidationException") {
                shouldThrow<PeerAuthValidationException> { result.getOrThrow() }
            }
        }

        `when`("the binding is a DPoP thumbprint") {
            val result = runCatching { service.requireKeycloakFor(accountId, "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs") }

            then("it refuses with PeerAuthValidationException") {
                shouldThrow<PeerAuthValidationException> { result.getOrThrow() }
            }
        }
    }

    given("a failed auth-password call") {
        val lockout = mockk<AccountLockoutService>()
        justRun { lockout.recordFailure(accountId, "WEB", AuthPasswordDescriptor.method) }
        val service = KeycloakToolCallsService(lockout, mockk())

        `when`("it is applied") {
            service.apply(accountId, AuthPasswordDescriptor, ToolOutcome.Failed.KnownAccountAuth(Text("Passwort falsch")))

            then("it charges the account's counter on the WEB channel") {
                verify(exactly = 1) { lockout.recordFailure(accountId, "WEB", "password") }
            }
        }
    }

    given("a successful auth-password call") {
        val lockout = mockk<AccountLockoutService>()
        justRun { lockout.recordSuccess(accountId) }
        val service = KeycloakToolCallsService(lockout, mockk())

        `when`("it is applied") {
            service.apply(accountId, AuthPasswordDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("password")))

            then("it resets the account's counter") {
                verify(exactly = 1) { lockout.recordSuccess(accountId) }
            }
        }
    }

    given("a successful enroll-password call") {
        val accountService = mockk<AccountService>()
        val claimInstance = slot<UUID>()
        val methodInstance = slot<UUID>()
        // The level exactly, not any(): MockK would invent an AcrLevel that its own check rejects.
        justRun { accountService.recordClaims(accountId, any(), AcrLevels.DEFAULT_REQUIRED_ACR, capture(claimInstance)) }
        every {
            accountService.addAuthenticationMethod(
                accountId = any(), method = any(), enrollmentRef = any(), enrolledUnderAcr = any(), details = any(),
                enrolledUnderAmr = any(), channel = any(), allowsMultipleInstances = any(), label = any(),
                instanceId = capture(methodInstance)
            )
        } returns mockk()
        val service = KeycloakToolCallsService(mockk(), accountService)
        val claims = listOf(Claim(AttributeType.PASSWORD_EXISTS, PASSWORD_EXISTS_MARKER, ClaimSource.of(EnrollPasswordDescriptor.toolId)))
        val enrollmentRef = EnrollmentRef("auth_password.enrollment", "11")
        val instanceDetails = mapOf<String, Any?>("hint" to "x")
        val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = enrollmentRef, claims = claims, instanceDetails = instanceDetails)

        `when`("it is applied") {
            service.apply(accountId, EnrollPasswordDescriptor, outcome)

            then("it records the claims under the default level") {
                verify(exactly = 1) { accountService.recordClaims(accountId, claims, AcrLevels.DEFAULT_REQUIRED_ACR, any()) }
            }

            then("it adds the method without enrolledUnderAcr, with the tool's instance details") {
                verify(exactly = 1) {
                    accountService.addAuthenticationMethod(
                        accountId = accountId, method = "password", enrollmentRef = enrollmentRef,
                        enrolledUnderAcr = null, details = instanceDetails,
                        enrolledUnderAmr = any(), channel = any(), allowsMultipleInstances = any(), label = any(),
                        instanceId = any()
                    )
                }
            }

            then("the claims point at the instance just added") {
                claimInstance.captured shouldBe methodInstance.captured
            }
        }
    }

    given("an outcome that does not fit the tool's role") {
        val service = KeycloakToolCallsService(mockk(), mockk())
        val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("auth_password.enrollment", "11"))

        `when`("a KNOWN_ACCOUNT_AUTH tool reports an enrollment") {
            val result = runCatching { service.apply(accountId, AuthPasswordDescriptor, outcome) }

            then("it refuses with IllegalStateException") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("outcomes that cannot be booked without a journey") {
        val service = KeycloakToolCallsService(mockk(), mockk())

        `when`("an ACCOUNT_LOOKUP_AUTH tool reports its failure") {
            val result = runCatching {
                service.apply(accountId, AuthPasswordLookupDescriptor, ToolOutcome.Failed.AccountLookupAuth(Text("Passwort falsch"), attempted = accountId?.let(Attempted::Account)))
            }

            then("it refuses with IllegalStateException") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("a tool is still in progress") {
            val result = runCatching { service.apply(accountId, AuthPasswordDescriptor, ToolOutcome.InProgress(nextStep = "auth")) }

            then("it refuses with IllegalStateException") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
