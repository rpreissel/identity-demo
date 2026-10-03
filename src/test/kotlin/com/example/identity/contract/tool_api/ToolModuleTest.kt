package com.example.identity.contract.tool_api

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import io.kotest.assertions.throwables.shouldThrow
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
        name = Text("Test"),
        proves = factors(FactorType.POSSESSION, FactorType.KNOWLEDGE, upTo = AcrLevel.LOA2),
    )
    qr.enroll("enroll-qr", hint = Text("Test"), optInOnly = true)
    qr.login("auth-qr", hint = Text("Test"), startStep = "waitForApp")
    qr.lookupLogin("auth-qr-lookup", hint = Text("Test"), startStep = "waitForApp")
    qr.approve("approve-qr", hint = Text("Test"))
    fun ToolModule.tool(role: ToolRole) = tools.single { it.role == role }

    given("a module with four roles") {
        then("its tools are listed in the order they were registered, each with the role of its factory") {
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

    given("a module and its tools' names") {
        val module = toolModule(method = "totp", name = Text("Einmalcode-App"), proves = factors(FactorType.POSSESSION, upTo = AcrLevel.LOA1))
        val enroll = module.enroll("enroll-totp", hint = Text("App einrichten"))
        val lookup = module.lookupLogin("auth-totp-lookup", hint = Text("E-Mail-Adresse + Code"), name = Text("Mit Code anmelden"))

        then("a tool is called by its module's name, with its own hint") {
            enroll.name shouldBe module.name
            enroll.hint shouldBe Text("App einrichten")
        }
        then("unless it names itself") {
            lookup.name shouldBe Text("Mit Code anmelden")
        }
    }

    given("an identification") {
        val nect = toolModule(
            method = "nect",
            name = Text("Test"),
            proves = factors(FactorType.POSSESSION, upTo = AcrLevel.LOA3),
        )
        val ident = nect.identify("ident-nect", hint = Text("Test"), also = setOf(AttributeType.STREET_ADDRESS))

        then("it always asserts name, given names and date of birth, so it can be found again (ADR-39)") {
            ident.claims.map { it.attributeType } shouldContainAll IDENTIFICATION_FINDABLE_BY + AttributeType.STREET_ADDRESS
        }
        then("its own claims name the tool as their source") {
            ident.claims.map { it.source }.toSet() shouldBe setOf(ClaimSource("ident-nect"))
            nect.source(ToolRole.IDENTIFICATION) shouldBe ClaimSource("ident-nect")
        }
    }

    given("a tool id that does not fit its role and method") {
        fun totp() = toolModule(method = "totp", name = Text("Test"), proves = factors(FactorType.POSSESSION, upTo = AcrLevel.LOA1))

        then("an id of another method is refused") {
            shouldThrow<IllegalArgumentException> { totp().enroll("enroll-sms", hint = Text("Test")) }.message shouldContain "must be called 'enroll-totp'"
        }
        then("an id of another role is refused") {
            shouldThrow<IllegalArgumentException> { totp().login("auth-totp-lookup", hint = Text("Test")) }.message shouldContain "must be called 'auth-totp'"
        }
        then("a role declared twice is refused") {
            val module = totp().apply { login("auth-totp", hint = Text("Test")) }
            shouldThrow<IllegalArgumentException> { module.login("auth-totp", hint = Text("Test")) }.message shouldContain "more than once"
        }
        then("an identification and a correlation together are refused: they share the id") {
            val module = totp().apply { identify("ident-totp", hint = Text("Test")) }
            shouldThrow<IllegalArgumentException> { module.correlate("ident-totp", hint = Text("Test"), claims = emptySet()) }.message shouldContain "more than once"
        }
    }

    given("a module the catalog has already collected") {
        val module = toolModule(method = "totp", name = Text("Test"), proves = factors(FactorType.POSSESSION, upTo = AcrLevel.LOA1))
        module.enroll("enroll-totp", hint = Text("Test"))
        module.tools

        then("a tool registered afterwards is refused instead of silently missing from the catalog") {
            shouldThrow<IllegalStateException> { module.login("auth-totp", hint = Text("Test")) }.message shouldContain "declare the tools of a module in its module file"
            module.tools.map { it.toolId.value } shouldContainExactly listOf("enroll-totp")
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
