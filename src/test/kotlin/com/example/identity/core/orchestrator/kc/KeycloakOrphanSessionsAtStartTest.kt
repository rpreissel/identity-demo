package com.example.identity.core.orchestrator.kc

import com.example.identity.core.account.AccountService
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.boot.DefaultApplicationArguments

class KeycloakOrphanSessionsAtStartTest : BehaviorSpec({

    fun startWith(accountIds: List<Long>): KeycloakRealmSessions {
        val accounts = mockk<AccountService> { every { allAccountIds() } returns accountIds }
        val sessions = mockk<KeycloakRealmSessions> { every { logoutAll() } just runs }
        KeycloakOrphanSessionsAtStart(accounts, sessions).run(DefaultApplicationArguments())
        return sessions
    }

    given("a start without a single account") {
        then("every Keycloak session belongs to an account that is gone, so all of them end") {
            val sessions = startWith(emptyList())
            verify(exactly = 1) { sessions.logoutAll() }
        }
    }

    given("a start with accounts") {
        then("their sessions stay") {
            val sessions = startWith(listOf(1L))
            verify(exactly = 0) { sessions.logoutAll() }
        }
    }

    given("Keycloak refusing") {
        then("the start goes on") {
            val accounts = mockk<AccountService> { every { allAccountIds() } returns emptyList() }
            val sessions = mockk<KeycloakRealmSessions> { every { logoutAll() } throws IllegalStateException("down") }
            KeycloakOrphanSessionsAtStart(accounts, sessions).run(DefaultApplicationArguments())
        }
    }
})
