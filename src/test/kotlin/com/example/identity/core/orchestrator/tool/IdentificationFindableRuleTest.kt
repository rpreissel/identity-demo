package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.tools.ident_eid.IdentEidDescriptor
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.tools.ident_nect.IdentNectDescriptor
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/**
 * ADR-39: every identification is findable again by name, first name and date of birth - the
 * change log's search key - so every identification procedure has to deliver all three. A
 * procedure that does not is refused at startup.
 */
class IdentificationFindableRuleTest : BehaviorSpec({

    given("the real identification procedures") {
        `when`("the registry is built") {
            val result = runCatching { ToolHandlerRegistry(listOf(IdentFscDescriptor, IdentEidDescriptor, IdentNectDescriptor)) }

            then("it starts - all three deliver name, first name and date of birth") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }
    }

    given("an identification procedure without a date of birth") {
        val withoutBirthDate = object : ToolDescriptor by IdentFscDescriptor {
            override val claims = IdentFscDescriptor.claims.filterNot { it.attributeType == AttributeType.BIRTH_DATE }.toSet()
        }

        `when`("the registry is built") {
            val result = runCatching { ToolHandlerRegistry(listOf(withoutBirthDate)) }

            then("it refuses to start, naming the missing attribute") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldContain "BIRTH_DATE"
            }
        }
    }
})
