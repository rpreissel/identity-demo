package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.Journeys
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.stereotype.Component
import tools.jackson.databind.jsontype.NamedType
import tools.jackson.module.kotlin.jacksonMapperBuilder
import kotlin.reflect.KClass

/**
 * Persists a [JourneyState] as `(stateType, state)` on the [AuthJourney] row. The discriminator
 * has its own column, so journeys stay queryable by position; the attributes differ per state and
 * travel as JSON. A state is read back under the run's own root ([Journey.stateRoot]), since two
 * journeys may share a state name. [write] is also the one place that refuses a state of another
 * journey: a transition does not know whose it is.
 * The states carry no serialization (ADR-40); `JourneyStateCodecTest` pins every type name.
 */
@Component
class JourneyStateCodec {

    private val mapper = jacksonMapperBuilder()
        .addMixIn(JourneyState::class.java, PersistedTypeName::class.java)
        .registerSubtypes(*Journeys.all.map { it.stateRoot }.flatMap(::concreteStates).distinct().map { NamedType(it.java, it.simpleName) }.toTypedArray())
        .build()

    /** `@t` is the simple class name, for every state, set in one place. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
    private interface PersistedTypeName

    fun write(journey: AuthJourney, state: JourneyState) {
        val kind = journey.requireKind()
        check(kind.stateRoot.isInstance(state)) { "${kind.name} has no state ${state::class.simpleName}, it belongs to another journey" }
        journey.stateType = state.javaClass.simpleName
        journey.state = mapper.writeValueAsString(state)
    }

    fun read(journey: AuthJourney): JourneyState {
        val json = checkNotNull(journey.state) { "Journey ${journey.journeyId} has no state" }
        return mapper.readValue(json, journey.requireKind().stateRoot.java)
    }

    companion object {
        /** The instantiable states under [root], through nested sealed levels. */
        fun concreteStates(root: KClass<out JourneyState>): List<KClass<out JourneyState>> =
            if (root.isSealed) root.sealedSubclasses.flatMap(::concreteStates) else listOf(root)
    }
}
