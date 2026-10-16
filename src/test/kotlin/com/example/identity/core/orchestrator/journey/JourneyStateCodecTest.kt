package com.example.identity.core.orchestrator.journey

import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import com.example.identity.core.orchestrator.domain.journeys.StepUpJourney
import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journey.state.WebSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.Journeys
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.time.Instant

/**
 * The persisted type names of the journey states (`@t`). The states carry no serialization
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md); the codec derives the names from the sealed
 * hierarchies. This pins them: a renamed state would leave its persisted journeys unreadable, so
 * the rename has to show up here first.
 */
class JourneyStateCodecTest : BehaviorSpec({

    val persistedNames = mapOf(
        FastAccessState::class to setOf("Start", "PreferredAuth", "AuthChoice", "Enrolling"),
        RegisterState::class to setOf("Start", "Closing", "Identifying", "DeviceLinkQuestion", "Assigning", "ConfirmingEmail", "SecondFactorKindObligation", "AuthChoice", "Enrolling"),
        LookupLoginState::class to setOf("Start", "Credential", "AdditionalFactor", "DeviceLinkQuestion"),
        WebSelectMethodState::class to setOf("SelectLoginMethod", "SelectStepUpMethod", "Start"),
        StepUpState::class to setOf("Start", "AuthChoice"),
        ManageAuthMethodsState::class to setOf("AddRequested", "RemoveRequested", "Enrolling", "RetractAttributeRequested", "ChangeRequested", "Changing"),
        ConfirmPeerLoginState::class to setOf("Requested", "Confirming"),
        DeleteAccountState::class to setOf("ConfirmPending"),
        FreshProofState::class to setOf("Start", "Reproving"),
        ReIdentifyState::class to setOf("OfferReIdent", "Identifying"),
    )

    val codec = JourneyStateCodec()

    given("the journeys the codec reads with") {
        then("their states' type names are exactly the persisted ones") {
            Journeys.all.map { it.stateRoot }
                .associateWith { root -> JourneyStateCodec.concreteStates(root).map { it.simpleName }.toSet() } shouldBe persistedNames
        }
    }

    given("a state of another journey") {
        `when`("it is written to a run") {
            val run = AuthJourney(channelSessionId = ChannelSessionId(UUID.randomUUID()), kind = StepUpJourney, createdAt = Instant.now())
            val result = runCatching { codec.write(run, FastAccessState.Start) }

            then("it is refused - a transition does not know whose it is, the codec does") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldContain "belongs to another journey"
            }
        }
    }

    given("every state without fields") {
        then("it survives a write and a read under its own journey") {
            Journeys.all.forEach { kind ->
                JourneyStateCodec.concreteStates(kind.stateRoot).mapNotNull { it.objectInstance }.forEach { state ->
                    val journey = AuthJourney(channelSessionId = ChannelSessionId(UUID.randomUUID()), kind = kind, createdAt = Instant.now())
                    codec.write(journey, state)
                    journey.stateType shouldBe state.javaClass.simpleName
                    codec.read(journey) shouldBe state
                }
            }
        }
    }
})
