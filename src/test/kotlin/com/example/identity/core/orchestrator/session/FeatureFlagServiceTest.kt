package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag
import com.example.identity.core.orchestrator.keycloak.KeycloakFeatureFlags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Clock

/** Keycloak's switch shares the flag store with the journey flags but never reach a strategy. */
class FeatureFlagServiceTest : BehaviorSpec({

    given("the enroll-first flag and the Keycloak switch are on") {
        val repository = mockk<FeatureFlagRepository> {
            every { findByEnabledTrue() } returns listOf(
                JourneyFeatureFlag.REGISTER_ENROLL_FIRST.key,
                KeycloakFeatureFlags.LOA1_PASSWORD
            ).map { FeatureFlag(flagKey = it).apply { enabled = true } }
        }

        `when`("a journey context collects the active flags") {
            val active = FeatureFlagService(repository, Clock.systemUTC()).activeFlags()

            then("only the flag a strategy reads is in it") {
                active shouldBe setOf(JourneyFeatureFlag.REGISTER_ENROLL_FIRST)
            }
        }
    }
})
