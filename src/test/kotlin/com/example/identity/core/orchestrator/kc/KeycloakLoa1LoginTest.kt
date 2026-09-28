package com.example.identity.core.orchestrator.kc

import com.example.identity.core.orchestrator.admin.Loa1LoginSwitch
import com.example.identity.core.orchestrator.session.FeatureFlagService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The switch finds its executions by provider id in the realm the migration builds, and the realm
 * starts where the switch stands without a flag row. Either mismatch would only show at runtime
 * against Keycloak, so the migration's text is checked here.
 */
class KeycloakLoa1LoginTest {

    private val realmScript = javaClass.getResource("/keycloak-migrations/V1__realm.kc.kts")!!.readText()

    @Test
    fun `every execution the switch toggles is created in the loa1 subflow`() {
        val providers = KeycloakLoa1Login.requirements(Loa1Login.KEYCLOAK_PASSWORD).map { it.first }
        assertThat(providers).hasSize(3)
        providers.forEach { provider ->
            assertThat(realmScript)
                .contains("""flows().addExecution("${KeycloakLoa1Login.LOA1_SUBFLOW}", mapOf("provider" to "$provider"))""")
        }
    }

    @Test
    fun `the migration creates the loa1 subflow in the state no flag row means`() {
        val noFlagRow = mockk<FeatureFlagService> { every { isEnabled(any()) } returns false }
        val initial = Loa1LoginSwitch(noFlagRow, mockk()).current()
        val subflow = Regex.escape(KeycloakLoa1Login.LOA1_SUBFLOW)
        KeycloakLoa1Login.requirements(initial).forEach { (provider, requirement) ->
            val p = Regex.escape(provider)
            val created = Regex(
                """"provider" to "$p"\)\)\s*""" +
                    """val id = childExecution\("$subflow"\) \{ it\.providerId == "$p" \}\s*""" +
                    """setRequirement\("$subflow", id, "(\w+)"\)"""
            ).find(realmScript)
            assertThat(created?.groupValues?.get(1)).describedAs(provider).isEqualTo(requirement)
        }
    }
}
