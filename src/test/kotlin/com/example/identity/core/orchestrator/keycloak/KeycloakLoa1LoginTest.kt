package com.example.identity.core.orchestrator.keycloak

import com.example.identity.core.orchestrator.admin.Loa1LoginSwitch
import com.example.identity.core.orchestrator.session.FeatureFlagService
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk

/**
 * The switch finds its executions by provider id in the realm the migration builds, and the realm
 * starts where the switch stands without a flag row. Either mismatch would only show at runtime
 * against Keycloak, so the migration's text is checked here. Which executions there are is pinned
 * by `Loa1LoginSwitchTest`.
 */
class KeycloakLoa1LoginTest : BehaviorSpec({

    val realmScript = javaClass.getResource("/keycloak-migrations/V1__realm.kc.kts")!!.readText()

    given("the realm migration and the executions the switch toggles") {
        val providers = KeycloakLoa1Login.requirements(Loa1Login.KEYCLOAK_PASSWORD).map { it.first }

        then("every one of them is created in the loa1 subflow") {
            providers.forEach { provider ->
                realmScript shouldContain """flows().addExecution("${KeycloakLoa1Login.LOA1_SUBFLOW}", mapOf("provider" to "$provider"))"""
            }
        }
    }

    given("the realm migration and the switch without a flag row") {
        val noFlagRow = mockk<FeatureFlagService> { every { isEnabled(any()) } returns false }
        val initial = Loa1LoginSwitch(noFlagRow, mockk()).current()
        val subflow = Regex.escape(KeycloakLoa1Login.LOA1_SUBFLOW)

        then("the migration creates the loa1 subflow in the state no flag row means") {
            KeycloakLoa1Login.requirements(initial).forEach { (provider, requirement) ->
                val p = Regex.escape(provider)
                val created = Regex(
                    """"provider" to "$p"\)\)\s*""" +
                        """val id = childExecution\("$subflow"\) \{ it\.providerId == "$p" \}\s*""" +
                        """setRequirement\("$subflow", id, "(\w+)"\)"""
                ).find(realmScript)
                withClue(provider) { created?.groupValues?.get(1) shouldBe requirement }
            }
        }
    }
})
