package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.domain.FeatureFlags
import com.example.identity.core.orchestrator.kc.KeycloakLoa1Login
import com.example.identity.core.orchestrator.kc.Loa1Login
import com.example.identity.core.orchestrator.session.FeatureFlagService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

/**
 * The switch's own rules, with the realm write mocked like in [LoginThemeSwitchTest]: realm first,
 * flag second, and a failed alignment at start never stops the orchestrator. Plus which executions
 * each variant turns on, in which order.
 */
class Loa1LoginSwitchTest {

    private val flags = mockk<FeatureFlagService>(relaxed = true)
    private val realm = mockk<KeycloakLoa1Login>(relaxed = true)
    private val switch = Loa1LoginSwitch(flags, realm)

    @Test
    fun `no flag means the orchestrator, the flag means Keycloak's password`() {
        every { flags.isEnabled(FeatureFlags.KEYCLOAK_LOA1_PASSWORD) } returns false
        assertThat(switch.current()).isEqualTo(Loa1Login.ORCHESTRATOR)
        every { flags.isEnabled(FeatureFlags.KEYCLOAK_LOA1_PASSWORD) } returns true
        assertThat(switch.current()).isEqualTo(Loa1Login.KEYCLOAK_PASSWORD)
    }

    @Test
    fun `switching writes the realm first, then remembers the choice`() {
        switch.switchTo(Loa1Login.KEYCLOAK_PASSWORD)
        verifyOrder {
            realm.apply(Loa1Login.KEYCLOAK_PASSWORD)
            flags.setEnabled(FeatureFlags.KEYCLOAK_LOA1_PASSWORD, true)
        }
    }

    @Test
    fun `a refused realm write leaves the flag as it was`() {
        every { realm.apply(any()) } throws IllegalStateException("Keycloak sagt nein")
        assertThatThrownBy { switch.switchTo(Loa1Login.ORCHESTRATOR) }.hasMessage("Keycloak sagt nein")
        verify(exactly = 0) { flags.setEnabled(any(), any(), any()) }
    }

    @Test
    fun `at start the realm follows the flag, and a failure there does not stop the start`() {
        every { flags.isEnabled(FeatureFlags.KEYCLOAK_LOA1_PASSWORD) } returns true
        switch.run(DefaultApplicationArguments())
        verify { realm.apply(Loa1Login.KEYCLOAK_PASSWORD) }

        every { realm.apply(any()) } throws IllegalStateException("Keycloak nicht erreichbar")
        switch.run(DefaultApplicationArguments())
    }

    @Test
    fun `each variant enables its own executions before it disables the other ones`() {
        assertThat(KeycloakLoa1Login.requirements(Loa1Login.KEYCLOAK_PASSWORD)).containsExactly(
            "auth-username-password-form" to "REQUIRED",
            "orchestrator-update-authenticator" to "REQUIRED",
            "orchestrator-authenticator" to "DISABLED",
        )
        assertThat(KeycloakLoa1Login.requirements(Loa1Login.ORCHESTRATOR)).containsExactly(
            "orchestrator-authenticator" to "REQUIRED",
            "auth-username-password-form" to "DISABLED",
            "orchestrator-update-authenticator" to "DISABLED",
        )
    }
}
