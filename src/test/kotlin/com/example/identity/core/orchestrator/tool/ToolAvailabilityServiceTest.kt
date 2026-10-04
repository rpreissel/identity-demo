package com.example.identity.core.orchestrator.tool

import com.example.identity.TEST_CLOCK
import com.example.identity.contract.tool_api.ToolVersion
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.catalogOf
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.demo.demo_mode.DemoMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.Optional

/**
 * Unit test of [ToolAvailabilityService]'s operator settings per channel type (ADR-32): the ranking
 * that orders a selection list per tool, and the switches that take one tool version out of one
 * channel only (ADR-51). The repositories are in-memory maps, so every setting is read back as the
 * next step would read it.
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

    given("auth-sms@1 switched off for the Web channel only") {
        val service = toolAvailabilityService()
        service.disable(ToolVersion.parse("auth-sms@1"), ChannelType.WEB, "web only")

        `when`("the App channel asks whether it is on") {
            val enabled = service.isEnabled(ToolVersion.parse("auth-sms@1"), ChannelType.APP)

            then("the App channel still offers it") {
                enabled shouldBe true
            }
        }

        `when`("the App channel lists its switched-off versions") {
            val disabled = service.disabledTools(ChannelType.APP)

            then("auth-sms@1 is not among them") {
                disabled shouldNotContain ToolVersion.parse("auth-sms@1")
            }
        }

        `when`("the Web channel asks whether it is on") {
            val enabled = service.isEnabled(ToolVersion.parse("auth-sms@1"), ChannelType.WEB)

            then("it is off there") {
                enabled shouldBe false
            }
        }
    }

    given("enroll-sms, served in versions 1 and 2, with version 1 switched off for the App channel") {
        val service = toolAvailabilityService()
        service.disable(ToolVersion.parse("enroll-sms@1"), ChannelType.APP, "old app")

        `when`("the App channel asks for both versions") {
            val v1 = service.isEnabled(ToolVersion.parse("enroll-sms@1"), ChannelType.APP)
            val v2 = service.isEnabled(ToolVersion.parse("enroll-sms@2"), ChannelType.APP)

            then("only version 1 is off") {
                v1 shouldBe false
                v2 shouldBe true
            }
        }
    }

    given("a switch for a version the server does not serve") {
        val service = toolAvailabilityService()

        `when`("it is set") {
            val unserved = runCatching { service.disable(ToolVersion.parse("auth-sms@9"), ChannelType.APP, null) }

            then("it is refused") {
                shouldThrow<IllegalArgumentException> { unserved.getOrThrow() }
            }
        }
    }

    given("a preset that locks enroll-sms without a version and auth-sms@1 with one") {
        val service = toolAvailabilityService(
            ToolDefaults(mapOf(ChannelType.WEB to ChannelToolDefaults(disabled = listOf("enroll-sms", "auth-sms@1"))))
        )

        `when`("the preset is applied") {
            service.applyDefaults()

            then("the plain entry locks every version, the other exactly its own") {
                service.disabledTools(ChannelType.WEB).map { it.toString() } shouldContainExactlyInAnyOrder listOf("enroll-sms@1", "enroll-sms@2", "auth-sms@1")
            }
        }
    }
})

/** The service over in-memory repositories, in demo mode, so only the operator's settings count. */
private fun toolAvailabilityService(defaults: ToolDefaults = ToolDefaults()): ToolAvailabilityService {
    val rows = mutableMapOf<ToolAvailabilityKey, ToolAvailability>()
    val repository = mockk<ToolAvailabilityRepository> {
        every { findById(any()) } answers { Optional.ofNullable(rows[firstArg()]) }
        every { findByChannel(any()) } answers { rows.values.filter { it.channel == firstArg() } }
        every { findAll() } answers { rows.values.toList() }
        every { deleteAll() } answers { rows.clear() }
        every { flush() } answers { }
        every { save(any<ToolAvailability>()) } answers {
            firstArg<ToolAvailability>().also { rows[ToolAvailabilityKey(it.wireTool, it.channel)] = it }
        }
    }
    val ranks = mutableMapOf<ToolOrderKey, ToolOrder>()
    val orderRepository = mockk<ToolOrderRepository> {
        every { findById(any()) } answers { Optional.ofNullable(ranks[firstArg()]) }
        every { findByChannel(any()) } answers { ranks.values.filter { it.channel == firstArg() } }
        every { deleteAll() } answers { ranks.clear() }
        every { deleteAll(any<Iterable<ToolOrder>>()) } answers { firstArg<Iterable<ToolOrder>>().toList().forEach { ranks.remove(ToolOrderKey(it.toolId, it.channel)) } }
        every { flush() } answers { }
        every { save(any<ToolOrder>()) } answers {
            firstArg<ToolOrder>().also { ranks[ToolOrderKey(it.toolId, it.channel)] = it }
        }
    }
    val registry = catalogOf("auth-sms", "auth-password", "auth-email", "enroll-sms")
    return ToolAvailabilityService(repository, orderRepository, registry, defaults, DemoMode(true), clock = TEST_CLOCK)
}
