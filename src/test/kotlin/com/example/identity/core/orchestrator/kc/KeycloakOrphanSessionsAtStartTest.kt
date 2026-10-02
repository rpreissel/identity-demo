package com.example.identity.core.orchestrator.kc

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.AccountService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.boot.DefaultApplicationArguments

/** At start, Keycloak sessions of accounts that are all gone end; the start itself never fails on it. */
class KeycloakOrphanSessionsAtStartTest : BehaviorSpec({

    fun accountsWith(accountIds: List<AccountId>) = mockk<AccountService> { every { allAccountIds() } returns accountIds }

    given("no account at all") {
        val sessions = mockk<KeycloakRealmSessions> { every { logoutAll() } just runs }
        val startup = KeycloakOrphanSessionsAtStart(accountsWith(emptyList()), sessions)

        `when`("the application starts") {
            startup.run(DefaultApplicationArguments())

            then("every Keycloak session belongs to an account that is gone, so all of them end") {
                verify(exactly = 1) { sessions.logoutAll() }
            }
        }
    }

    given("existing accounts") {
        val sessions = mockk<KeycloakRealmSessions> { every { logoutAll() } just runs }
        val startup = KeycloakOrphanSessionsAtStart(accountsWith(listOf(AccountId(1))), sessions)

        `when`("the application starts") {
            startup.run(DefaultApplicationArguments())

            then("their sessions stay") {
                verify(exactly = 0) { sessions.logoutAll() }
            }
        }
    }

    given("no account at all, and a Keycloak that refuses the logout") {
        val sessions = mockk<KeycloakRealmSessions> { every { logoutAll() } throws IllegalStateException("down") }
        val startup = KeycloakOrphanSessionsAtStart(accountsWith(emptyList()), sessions)

        `when`("the application starts") {
            val result = runCatching { startup.run(DefaultApplicationArguments()) }

            then("the start goes on") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }
    }
})
