package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.session.FeatureFlagService
import com.example.identity.core.orchestrator.tool.ToolAvailabilityService
import com.example.identity.demo.demo_mode.DemoMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.mock.env.MockEnvironment

/** The endpoint has no login; what the management port reports stays there outside demo mode. */
class ServerInfoControllerTest : BehaviorSpec({

    given("an instance outside demo mode") {
        val healthEndpoint = mockk<HealthEndpoint>()
        val toolAvailability = mockk<ToolAvailabilityService> { every { disabledEntries() } returns emptyList() }
        val controller = ServerInfoController(
            environment = MockEnvironment(),
            featureFlagService = mockk<FeatureFlagService>(relaxed = true),
            toolAvailabilityService = toolAvailability,
            loa1LoginSwitch = mockk(),
            healthEndpoint = healthEndpoint,
            meterRegistry = SimpleMeterRegistry(),
            demoMode = DemoMode(on = false),
        )

        `when`("the welcome page asks for the server status") {
            val info = controller.get()

            then("it shows no health, counters or latencies") {
                info.operations shouldBe null
                info.demoMode shouldBe false
                verify(exactly = 0) { healthEndpoint.health() }
            }
        }
    }
})
