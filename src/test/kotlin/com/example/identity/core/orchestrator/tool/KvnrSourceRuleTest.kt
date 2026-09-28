package com.example.identity.core.orchestrator.tool

import com.example.identity.tools.ident_eid.IdentEidDescriptor
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.tools.ident_kvnr.IdentKvnrDescriptor
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ToolDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/**
 * Identity matching trusts a KVNR only because the Personenverzeichnis vouched for it. A tool that
 * declares one from anywhere else is refused at startup.
 */
class KvnrSourceRuleTest : BehaviorSpec({

    given("the real catalog") {
        then("only Personenverzeichnis-backed tools declare a KVNR, so it starts") {
            ToolHandlerRegistry(listOf(IdentFscDescriptor, IdentKvnrDescriptor, IdentEidDescriptor))
        }
    }

    given("a tool that reads a KVNR itself") {
        val cardReader = object : ToolDescriptor by IdentEidDescriptor {
            override val claims = IdentEidDescriptor.claims + ClaimDeclaration(AttributeType.KVNR, ClaimSource.of(IdentEidDescriptor.toolId))
        }
        then("the registry refuses to start") {
            shouldThrow<IllegalStateException> { ToolHandlerRegistry(listOf(cardReader)) }.message shouldContain "Personenverzeichnis"
        }
    }
})
