package com.example.identity.core.orchestrator.tool

import com.example.identity.TEST_CLOCK
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.catalogOf
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.demo.demo_mode.DemoMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.Optional

/**
 * Unit test of [ToolAvailabilityService]'s operator settings per channel type (ADR-32): the ranking
 * that orders a selection list, and the switches that take a tool out of one channel only. The
 * repository is an in-memory map, so every setting is read back as the next step would read it.
 */
class ToolAvailabilityServiceTest : BehaviorSpec({

    val sms = tool("auth-sms").toolId
    val password = tool("auth-password").toolId
    val email = tool("auth-email").toolId

    given("an App channel ranked password before sms") {
        val service = toolAvailabilityService()
        service.setOrder(ChannelType.APP, listOf(password.value, sms.value))

        `when`("ordering an offer of sms, email and password") {
            val ordered = service.ordered(ChannelType.APP, listOf(sms, email, password))

            then("the ranked tools come first, in exactly that order, the unranked one behind them") {
                ordered shouldBe listOf(password, sms, email)
            }
        }
    }

    given("an App channel ranked password before sms, then reversed") {
        val service = toolAvailabilityService()
        service.setOrder(ChannelType.APP, listOf(password.value, sms.value))
        service.setOrder(ChannelType.APP, listOf(sms.value, password.value))

        `when`("ordering an offer of password and sms") {
            val ordered = service.ordered(ChannelType.APP, listOf(password, sms))

            then("the new order applies") {
                ordered shouldBe listOf(sms, password)
            }
        }
    }

    given("an App channel ranked password and sms, then ranked sms alone") {
        val service = toolAvailabilityService()
        service.setOrder(ChannelType.APP, listOf(password.value, sms.value))
        service.setOrder(ChannelType.APP, listOf(sms.value))

        `when`("reading the ranking") {
            val rank = service.rankOf(ChannelType.APP)

            then("password lost its rank instead of keeping the old one") {
                rank shouldBe mapOf(sms.value to 0)
            }
        }
    }

    given("a ranking set for the App channel only") {
        val service = toolAvailabilityService()
        service.setOrder(ChannelType.APP, listOf(sms.value, password.value))

        `when`("ordering the Web channel's offer") {
            val ordered = service.ordered(ChannelType.WEB, listOf(sms, password))

            then("the Web channel keeps the default order, by role and then method") {
                ordered shouldBe listOf(password, sms)
            }
        }
    }

    given("sms switched off for the Web channel only") {
        val service = toolAvailabilityService()
        service.disable(sms.value, ChannelType.WEB, "web only")

        `when`("the App channel asks whether sms is on") {
            val enabled = service.isEnabled(sms.value, ChannelType.APP)

            then("the App channel still offers it") {
                enabled shouldBe true
            }
        }

        `when`("the App channel lists its switched-off tools") {
            val disabled = service.disabledToolIds(ChannelType.APP)

            then("sms is not among them") {
                disabled shouldNotContain sms.value
            }
        }

        `when`("the Web channel asks whether sms is on") {
            val enabled = service.isEnabled(sms.value, ChannelType.WEB)

            then("it is off there") {
                enabled shouldBe false
            }
        }
    }
})

/** The service over an in-memory repository, in demo mode, so only the operator's settings count. */
private fun toolAvailabilityService(): ToolAvailabilityService {
    val rows = mutableMapOf<ToolAvailabilityKey, ToolAvailability>()
    val repository = mockk<ToolAvailabilityRepository> {
        every { findById(any()) } answers { Optional.ofNullable(rows[firstArg()]) }
        every { findByChannel(any()) } answers { rows.values.filter { it.channel == firstArg() } }
        every { save(any<ToolAvailability>()) } answers {
            firstArg<ToolAvailability>().also { rows[ToolAvailabilityKey(it.toolId, it.channel)] = it }
        }
    }
    val registry = catalogOf("auth-sms", "auth-password", "auth-email")
    return ToolAvailabilityService(repository, registry, ToolDefaults(), DemoMode(true), clock = TEST_CLOCK)
}
