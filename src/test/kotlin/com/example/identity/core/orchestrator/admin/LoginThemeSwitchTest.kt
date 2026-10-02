package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.keycloak.KeycloakFeatureFlags
import com.example.identity.core.orchestrator.keycloak.KeycloakRealmLoginTheme
import com.example.identity.core.orchestrator.keycloak.LoginTheme
import com.example.identity.core.orchestrator.session.FeatureFlagService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.boot.DefaultApplicationArguments

/**
 * The switch's own rules - the realm write (Keycloak admin API) is mocked here; the compose stack
 * exercises it for real. Realm first, flag second, so a refused write changes nothing; and a
 * failed alignment at start never stops the orchestrator.
 */
class LoginThemeSwitchTest : BehaviorSpec({

    /** The switch over relaxed mocks, the Keycloakify flag set to [keycloakify]. */
    class Fixture(keycloakify: Boolean = false) {
        val flags = mockk<FeatureFlagService>(relaxed = true) {
            every { isEnabled(KeycloakFeatureFlags.LOGIN_KEYCLOAKIFY) } returns keycloakify
        }
        val realm = mockk<KeycloakRealmLoginTheme>(relaxed = true)
        val switch = LoginThemeSwitch(flags, realm)
    }

    given("no Keycloakify flag") {
        val fixture = Fixture(keycloakify = false)

        `when`("reading the current theme") {
            val theme = fixture.switch.current()

            then("it is FreeMarker") {
                theme shouldBe LoginTheme.FREEMARKER
            }
        }

        `when`("switching to Keycloakify") {
            fixture.switch.switchTo(LoginTheme.KEYCLOAKIFY)

            then("the realm is written first, then the choice is remembered") {
                verifyOrder {
                    fixture.realm.apply(LoginTheme.KEYCLOAKIFY)
                    fixture.flags.setEnabled(KeycloakFeatureFlags.LOGIN_KEYCLOAKIFY, true)
                }
            }
        }
    }

    given("a Keycloak that refuses the realm write") {
        val fixture = Fixture()
        every { fixture.realm.apply(any()) } throws IllegalStateException("Keycloak says no")

        `when`("switching to Keycloakify") {
            val result = runCatching { fixture.switch.switchTo(LoginTheme.KEYCLOAKIFY) }

            then("the refusal reaches the caller, and the flag stays as it was") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldBe "Keycloak says no"
                verify(exactly = 0) { fixture.flags.setEnabled(any(), any(), any()) }
            }
        }
    }

    given("the Keycloakify flag") {
        val fixture = Fixture(keycloakify = true)

        `when`("reading the current theme") {
            val theme = fixture.switch.current()

            then("it is Keycloakify") {
                theme shouldBe LoginTheme.KEYCLOAKIFY
            }
        }

        `when`("the orchestrator starts") {
            fixture.switch.run(DefaultApplicationArguments())

            then("the realm follows the flag") {
                verify { fixture.realm.apply(LoginTheme.KEYCLOAKIFY) }
            }
        }
    }

    given("the Keycloakify flag, and a Keycloak that cannot be reached") {
        val fixture = Fixture(keycloakify = true)
        every { fixture.realm.apply(any()) } throws IllegalStateException("Keycloak unreachable")

        `when`("the orchestrator starts") {
            val result = runCatching { fixture.switch.run(DefaultApplicationArguments()) }

            then("the failed alignment does not stop the start") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }
    }
})
