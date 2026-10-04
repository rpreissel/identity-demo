package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.tool_api.ToolVersion
import com.example.identity.TEST_CLOCK
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.catalogOf
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.demo.demo_mode.DemoMode
import com.example.identity.core.orchestrator.domain.ChannelType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.Optional

/**
 * A tool that declares itself demo-only ([com.example.identity.contract.tool_api.Tool.demoOnly])
 * is unavailable outside `demo.mode` - even where an operator setting explicitly enables it.
 */
class DemoOnlyToolAvailabilityTest : BehaviorSpec({

    val registry = catalogOf("auth-device", "enroll-device", "auth-sms")
    val repository = mockk<ToolAvailabilityRepository>()
    // An operator row that explicitly switches auth-device ON - it must not matter outside demo mode.
    every { repository.findById(any()) } answers {
        val key = firstArg<ToolAvailabilityKey>()
        Optional.of(ToolAvailability(key.wireTool, key.channel).apply { enabled = true })
    }
    every { repository.findByChannel(any()) } returns emptyList()

    fun service(demoMode: Boolean) = ToolAvailabilityService(repository, mockk(), registry, ToolDefaults(), DemoMode(demoMode), clock = TEST_CLOCK)

    given("the tools whose level this instance cannot back (ADR-36)") {
        then("the device tools (claimed user verification), KOBIL, eID and Nect (simulated counterparts) declare themselves demo-only") {
            listOf(tool("auth-device"), tool("enroll-device"), tool("auth-kobil"), tool("enroll-kobil"), tool("ident-eid"), tool("ident-nect"))
                .forEach { (it.demoOnly != null) shouldBe true }
            tool("auth-sms").demoOnly shouldBe null
        }
    }

    given("demo.mode=false") {
        val service = service(demoMode = false)
        then("demo-only tools are off in every channel, whatever the operator set; others are untouched") {
            ChannelType.entries.forEach { channel ->
                service.isEnabled(ToolVersion.parse("auth-device@1"), channel) shouldBe false
                service.isEnabled(ToolVersion.parse("enroll-device@1"), channel) shouldBe false
                service.isEnabled(ToolVersion.parse("auth-sms@1"), channel) shouldBe true
                service.disabledTools(channel) shouldContainAll setOf(ToolVersion.parse("auth-device@1"), ToolVersion.parse("enroll-device@1"))
                service.disabledTools(channel) shouldNotContain ToolVersion.parse("auth-sms@1")
            }
        }
    }

    given("demo.mode=true") {
        val service = service(demoMode = true)
        then("demo-only tools follow the operator settings like any other") {
            service.isEnabled(ToolVersion.parse("auth-device@1"), ChannelType.APP) shouldBe true
            service.disabledTools(ChannelType.APP) shouldNotContain ToolVersion.parse("auth-device@1")
        }
    }
})
