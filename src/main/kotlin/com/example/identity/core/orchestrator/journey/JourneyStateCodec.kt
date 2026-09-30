package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.WebSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.LogoutState
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import org.springframework.stereotype.Component
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kotlin.reflect.KClass
import com.example.identity.core.orchestrator.domain.AuthIntent

/**
 * Persists a [JourneyState] as `(stateType, state)` on the [AuthJourney] row. The discriminator
 * has its own column, so journeys stay queryable by position; the attributes differ per state and
 * travel as JSON. The root to read back follows from the [AuthIntent], since two intents may share
 * a state name. REGISTER has two roots with disjoint names, so [read] tries both.
 * The states carry no serialization (ADR-40); `JourneyStateCodecTest` pins every type name.
 */
@Component
class JourneyStateCodec {

    private val mapper = jacksonMapperBuilder()
        .addMixIn(JourneyState::class.java, PersistedTypeName::class.java)
        .registerSubtypes(*STATE_ROOTS.flatMap(::concreteStates).distinct().map { NamedType(it.java, it.simpleName) }.toTypedArray())
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
        val intent = journey.requireIntent()
        if (intent == AuthIntent.REGISTER) {
            return try {
                mapper.readValue(json, RegisterState::class.java)
            } catch (e: InvalidTypeIdException) {
                mapper.readValue(json, RegisterEnrollFirstState::class.java)
            }
        }
        return mapper.readValue(json, rootOf(intent))
    }

    companion object {
    /** Every sealed root a journey's state is read back as: one per intent, two for REGISTER. */
        val STATE_ROOTS: List<KClass<out JourneyState>> = listOf(
            FastAccessState::class, RegisterState::class, RegisterEnrollFirstState::class, LookupLoginState::class,
            WebSelectMethodState::class, StepUpState::class, ManageAuthMethodsState::class,
            ConfirmPeerLoginState::class, DeleteAccountState::class, LogoutState::class, ReIdentifyState::class,
        )

        /** The instantiable states under [root], through nested sealed levels. */
        fun concreteStates(root: KClass<out JourneyState>): List<KClass<out JourneyState>> =
            if (root.isSealed) root.sealedSubclasses.flatMap(::concreteStates) else listOf(root)
    }

    private fun rootOf(intent: AuthIntent): Class<out JourneyState> = when (intent) {
        AuthIntent.FAST_ACCESS -> FastAccessState::class.java
        AuthIntent.REGISTER -> error("REGISTER is handled separately in read() - see its own doc")
        AuthIntent.LOOKUP_LOGIN -> LookupLoginState::class.java
        AuthIntent.WEB_SELECT_METHOD -> WebSelectMethodState::class.java
        AuthIntent.STEP_UP -> StepUpState::class.java
        AuthIntent.MANAGE_AUTH_METHODS -> ManageAuthMethodsState::class.java
        AuthIntent.CONFIRM_PEER_LOGIN -> ConfirmPeerLoginState::class.java
        AuthIntent.DELETE_ACCOUNT -> DeleteAccountState::class.java
        AuthIntent.LOGOUT -> LogoutState::class.java
        AuthIntent.RE_IDENTIFY -> ReIdentifyState::class.java
    }
}
