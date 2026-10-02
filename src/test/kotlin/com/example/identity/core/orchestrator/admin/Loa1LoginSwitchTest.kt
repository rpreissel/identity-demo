package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.domain.FeatureFlags
import com.example.identity.core.orchestrator.kc.KeycloakLoa1Login
import com.example.identity.core.orchestrator.kc.Loa1Login
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
 * The switch's own rules, with the realm write mocked like in [LoginThemeSwitchTest]: realm first,
 * flag second, and a failed alignment at start never stops the orchestrator. Plus which executions
 * each variant turns on, in which order.
 */
class Loa1LoginSwitchTest : BehaviorSpec({

    /** The switch over relaxed mocks, the Keycloak password flag set to [keycloakPassword]. */
    class Fixture(keycloakPassword: Boolean = false) {
        val flags = mockk<FeatureFlagService>(relaxed = true) {
            every { isEnabled(FeatureFlags.KEYCLOAK_LOA1_PASSWORD) } returns keycloakPassword
        }
        val realm = mockk<KeycloakLoa1Login>(relaxed = true)
        val switch = Loa1LoginSwitch(flags, realm)
    }

    given("no Keycloak password flag") {
        val fixture = Fixture(keycloakPassword = false)

        `when`("reading the current loa1 login") {
            val login = fixture.switch.current()

            then("the orchestrator asks") {
                login shouldBe Loa1Login.ORCHESTRATOR
            }
        }

        `when`("switching to Keycloak's password") {
            fixture.switch.switchTo(Loa1Login.KEYCLOAK_PASSWORD)

            then("the realm is written first, then the choice is remembered") {
                verifyOrder {
                    fixture.realm.apply(Loa1Login.KEYCLOAK_PASSWORD)
                    fixture.flags.setEnabled(FeatureFlags.KEYCLOAK_LOA1_PASSWORD, true)
                }
            }
        }
    }

    given("a Keycloak that refuses the realm write") {
        val fixture = Fixture()
        every { fixture.realm.apply(any()) } throws IllegalStateException("Keycloak says no")

        `when`("switching to the orchestrator") {
            val result = runCatching { fixture.switch.switchTo(Loa1Login.ORCHESTRATOR) }

            then("the refusal reaches the caller, and the flag stays as it was") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldBe "Keycloak says no"
                verify(exactly = 0) { fixture.flags.setEnabled(any(), any(), any()) }
            }
        }
    }

    given("the Keycloak password flag") {
        val fixture = Fixture(keycloakPassword = true)

        `when`("reading the current loa1 login") {
            val login = fixture.switch.current()

            then("Keycloak's password asks") {
                login shouldBe Loa1Login.KEYCLOAK_PASSWORD
            }
        }

        `when`("the orchestrator starts") {
            fixture.switch.run(DefaultApplicationArguments())

            then("the realm follows the flag") {
                verify { fixture.realm.apply(Loa1Login.KEYCLOAK_PASSWORD) }
            }
        }
    }

    given("the Keycloak password flag, and a Keycloak that cannot be reached") {
        val fixture = Fixture(keycloakPassword = true)
        every { fixture.realm.apply(any()) } throws IllegalStateException("Keycloak unreachable")

        `when`("the orchestrator starts") {
            val result = runCatching { fixture.switch.run(DefaultApplicationArguments()) }

            then("the failed alignment does not stop the start") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }
    }

    // Each variant enables its own executions before it disables the other ones.
    given("the Keycloak password variant") {
        then("it requires the password form and the update step, then disables the orchestrator's authenticator") {
            KeycloakLoa1Login.requirements(Loa1Login.KEYCLOAK_PASSWORD) shouldBe listOf(
                "auth-username-password-form" to "REQUIRED",
                "orchestrator-update-authenticator" to "REQUIRED",
                "orchestrator-authenticator" to "DISABLED",
            )
        }
    }

    given("the orchestrator variant") {
        then("it requires the orchestrator's authenticator, then disables the password form and the update step") {
            KeycloakLoa1Login.requirements(Loa1Login.ORCHESTRATOR) shouldBe listOf(
                "orchestrator-authenticator" to "REQUIRED",
                "auth-username-password-form" to "DISABLED",
                "orchestrator-update-authenticator" to "DISABLED",
            )
        }
    }
})
