package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.Journeys
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.stereotype.Component
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.databind.jsontype.NamedType
import tools.jackson.module.kotlin.jacksonMapperBuilder
import kotlin.reflect.KClass

/**
 * Persists a [JourneyState] as `(stateType, state)` on the [AuthJourney] row. The discriminator
 * has its own column, so journeys stay queryable by position; the attributes differ per state and
 * travel as JSON. The roots to read back come from the run's kind
 * ([Journey.stateRoots]), since two journeys may share a state name; a journey with several
 * roots has disjoint names in them, so [read] tries each in turn.
 * The states carry no serialization (ADR-40); `JourneyStateCodecTest` pins every type name.
 */
@Component
class JourneyStateCodec {

    private val mapper = jacksonMapperBuilder()
        .addMixIn(JourneyState::class.java, PersistedTypeName::class.java)
        .registerSubtypes(*Journeys.all.flatMap { it.stateRoots }.flatMap(::concreteStates).distinct().map { NamedType(it.java, it.simpleName) }.toTypedArray())
        .build()

    /** `@t` is the simple class name, for every state, set in one place. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
    private interface PersistedTypeName

    fun write(journey: AuthJourney, state: JourneyState) {
        journey.stateType = state.javaClass.simpleName
        journey.state = mapper.writeValueAsString(state)
    }

    fun read(journey: AuthJourney): JourneyState {
        val json = checkNotNull(journey.state) { "Journey ${journey.journeyId} has no state" }
        val roots = journey.requireKind().stateRoots
        roots.dropLast(1).forEach { root ->
            try {
                return mapper.readValue(json, root.java)
            } catch (_: InvalidTypeIdException) {
                // Not a state under this root; the next one may know it.
            }
        }
        return mapper.readValue(json, roots.last().java)
    }

    companion object {
        /** The instantiable states under [root], through nested sealed levels. */
        fun concreteStates(root: KClass<out JourneyState>): List<KClass<out JourneyState>> =
            if (root.isSealed) root.sealedSubclasses.flatMap(::concreteStates) else listOf(root)
    }
}
