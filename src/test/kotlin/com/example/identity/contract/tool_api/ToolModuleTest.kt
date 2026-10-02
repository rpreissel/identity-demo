package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * What a [ToolModule] derives for its tools, so no tool states it again (docs/03-tool-architektur.md
 * #1). No Spring: the modules are plain values.
 */
class ToolModuleTest : BehaviorSpec({

    val qr = toolModule(
        method = "qr",
        proves = factors(FactorType.POSSESSION, FactorType.KNOWLEDGE, upTo = AcrLevel.LOA2),
        tools = listOf(
            enroll("enroll-qr", optInOnly = true),
            login("auth-qr", startStep = "waitForApp"),
            lookupLogin("auth-qr-lookup", startStep = "waitForApp"),
            approve("approve-qr"),
        ),
    )
    fun ToolModule.tool(role: ToolRole) = tools.single { it.role == role }

    given("a module with four roles") {
        then("each tool's role follows from its factory") {
            qr.tools.map { it.role } shouldContainExactly listOf(
                ToolRole.ENROLLMENT, ToolRole.KNOWN_ACCOUNT_AUTH, ToolRole.ACCOUNT_LOOKUP_AUTH, ToolRole.PEER_APPROVAL,
            )
        }
        then("each tool carries its declared id") {
            qr.tools.map { it.toolId.value } shouldContainExactly listOf("enroll-qr", "auth-qr", "auth-qr-lookup", "approve-qr")
        }
        then("every tool carries the module's ceiling") {
            qr.tools.map { it.maxAcr }.toSet() shouldBe setOf(AcrLevel.LOA2)
        }
        then("an opt-in and a peer approval provide no factor, the auth tools the module's") {
            qr.tool(ToolRole.ENROLLMENT).factorTypes shouldBe emptySet()
            qr.tool(ToolRole.PEER_APPROVAL).factorTypes shouldBe emptySet()
            qr.tool(ToolRole.KNOWN_ACCOUNT_AUTH).factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        }
        then("a declared start step wins, otherwise the role's") {
            qr.tool(ToolRole.KNOWN_ACCOUNT_AUTH).startStep shouldBe "waitForApp"
            qr.tool(ToolRole.ENROLLMENT).startStep shouldBe "enroll"
        }
    }

    given("an identification") {
        val nect = toolModule(
            method = "nect",
            proves = factors(FactorType.POSSESSION, upTo = AcrLevel.LOA3),
            tools = listOf(identify("ident-nect", also = setOf(AttributeType.STREET_ADDRESS))),
        )
        val ident = nect.tools.single()

        then("it always asserts name, given names and date of birth, so it can be found again (ADR-39)") {
            ident.claims.map { it.attributeType } shouldContainAll IDENTIFICATION_FINDABLE_BY + AttributeType.STREET_ADDRESS
        }
        then("its own claims name the tool as their source") {
            ident.claims.map { it.source }.toSet() shouldBe setOf(ClaimSource("ident-nect"))
            nect.source(ToolRole.IDENTIFICATION) shouldBe ClaimSource("ident-nect")
        }
    }

    given("a tool id that does not fit its role and method") {
        fun build(vararg tools: RoleSpec) = runCatching {
            toolModule(method = "totp", proves = factors(FactorType.POSSESSION, upTo = AcrLevel.LOA1), tools = tools.toList())
        }

        then("an id of another method is refused") {
            build(enroll("enroll-sms")).exceptionOrNull()!!.message shouldContain "must be called 'enroll-totp'"
        }
        then("an id of another role is refused") {
            build(login("auth-totp-lookup")).exceptionOrNull()!!.message shouldContain "must be called 'auth-totp'"
        }
        then("a role declared twice is refused") {
            build(login("auth-totp"), login("auth-totp")).exceptionOrNull()!!.message shouldContain "more than once"
        }
        then("an identification and a correlation together are refused: they share the id") {
            build(identify("ident-totp"), correlate("ident-totp", claims = emptySet())).exceptionOrNull()!!.message shouldContain "more than once"
        }
    }

    given("a run of auth-qr that reports nothing beyond its tool") {
        val outcome = ToolOutcome.Completed.Authenticated()
        val tool = qr.tool(ToolRole.KNOWN_ACCOUNT_AUTH)

        then("its amr, level and factors are the tool's") {
            tool.amrOf(outcome) shouldBe listOf("qr")
            tool.levelOf(outcome) shouldBe AcrLevel.LOA2
            tool.factorsOf(outcome) shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            tool.staysWithin(outcome) shouldBe true
        }
    }

    given("a run of approve-qr, which proves nothing on its own channel") {
        then("it reports no amr") {
            qr.tool(ToolRole.PEER_APPROVAL).amrOf(ToolOutcome.Completed.Approved()) shouldBe emptyList()
        }
    }

    given("a one-per-device module with an instance on key-a") {
        val device = StrategyTestFixtures.tool("auth-device").module

        then("the instance lives on key-a only") {
            device.livesOn("key-a", "key-a") shouldBe true
            device.livesOn("key-a", "key-b") shouldBe false
        }
        then("a caller without a key (a WEB channel) never matches it") {
            device.livesOn("key-a", null) shouldBe false
        }
        then("a method that is not one-per-device lives on no key") {
            StrategyTestFixtures.tool("auth-sms").module.livesOn(null, null) shouldBe false
        }
    }
})
