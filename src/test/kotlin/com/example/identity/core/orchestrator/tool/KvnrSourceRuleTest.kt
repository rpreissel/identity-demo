package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.tools.ident_eid.IdentEidDescriptor
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.tools.ident_kvnr.IdentKvnrDescriptor
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/**
 * Identity matching trusts a KVNR only because the Personenverzeichnis vouched for it. A tool that
 * declares one from anywhere else is refused at startup.
 */
class KvnrSourceRuleTest : BehaviorSpec({

    given("the real catalog") {
        `when`("the registry is built") {
            val result = runCatching { ToolHandlerRegistry(listOf(IdentFscDescriptor, IdentKvnrDescriptor, IdentEidDescriptor)) }

            then("it starts - only Personenverzeichnis-backed tools declare a KVNR") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }
    }

    given("a tool that reads a KVNR itself") {
        val cardReader = object : ToolDescriptor by IdentEidDescriptor {
            override val claims = IdentEidDescriptor.claims + ClaimDeclaration(AttributeType.KVNR, ClaimSource(IdentEidDescriptor.toolId.value))
        }

        `when`("the registry is built") {
            val result = runCatching { ToolHandlerRegistry(listOf(cardReader)) }

            then("it refuses to start") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldContain "Personenverzeichnis"
            }
        }
    }
})
