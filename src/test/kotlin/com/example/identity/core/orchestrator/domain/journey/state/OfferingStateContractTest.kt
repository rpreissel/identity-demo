package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.architecture.mainClassesIn
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.state.ToolRef
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/**
 * Every offering state implements [OfferingState.declining] as its own
 * `copy(declined = declined + toolId, active = null)`, since a `data class` cannot inherit `copy`.
 * Forgetting `active = null` would leave the declined tool running. The states are found
 * reflectively, so a new one is covered without being listed here.
 */
class OfferingStateContractTest : BehaviorSpec({

    /**
     * The sample [Offer] every state under test is built around. It has a running tool, otherwise
     * the "clears the active tool" assertion below would pass whatever `declining` does.
     */
    val sampleOffer = Offer(
        offered = listOf(ToolId("probe-a"), ToolId("probe-b")),
        active = ToolRef(ToolId("probe-a"), ToolSessionId(UUID.randomUUID()), "input")
    )

    /** A value for a constructor parameter the state needs but this contract does not care about. */
    fun sampleFor(parameter: KParameter): Any? = when (parameter.type.classifier) {
        Offer::class -> sampleOffer
        AcrLevel::class -> AcrLevel.LOA1
        Boolean::class -> false
        String::class -> "probe"
        Long::class -> 1L
        UUID::class -> UUID.randomUUID()
        else -> null
    }

    fun instantiate(type: KClass<*>): OfferingState? {
        val constructor = type.primaryConstructor ?: return null
        val arguments = constructor.parameters.mapNotNull { parameter ->
            val value = sampleFor(parameter)
            when {
                value != null -> parameter to value
                // Anything without a sample must be optional; otherwise the state fails below
                // instead of being skipped.
                parameter.isOptional -> null
                else -> return null
            }
        }.toMap()
        return constructor.callBy(arguments) as? OfferingState
    }

    val implementations = mainClassesIn("com.example.identity.core.orchestrator.domain.journey.state")
        .filter { it.isAssignableTo(OfferingState::class.java) && !it.isInterface }
        .map { Class.forName(it.name).kotlin }

    given("every state that offers tools") {
        then("all of them can be exercised here - none is silently skipped") {
            implementations.shouldNotBeEmpty()
            val unconstructible = implementations.filter { instantiate(it) == null }
            withClue("no sample value for a required constructor parameter - add one to sampleFor()") {
                unconstructible.map { it.simpleName } shouldBe emptyList()
            }
        }

        then("declining a tool records it AND stops the tool that was running") {
            implementations.forEach { type ->
                val state = instantiate(type)!!
                withClue("${type.simpleName}: the sample must HAVE a running tool, else the assertion below proves nothing") {
                    state.active.shouldNotBeNull()
                }
                val declined = state.declining(ToolId("probe-a"))

                withClue("${type.simpleName} must record the declined tool") {
                    declined.declined shouldBe state.declined + ToolId("probe-a")
                }
                // The half that is easy to forget: a decline that leaves `active` set would keep
                // the abandoned tool addressable while the journey has already moved past it.
                withClue("${type.simpleName} must clear the active tool when one is declined") {
                    declined.active.shouldBeNull()
                }
                withClue("${type.simpleName} must not change what it offers") {
                    declined.offered shouldBe state.offered
                }
            }
        }
    }
})
