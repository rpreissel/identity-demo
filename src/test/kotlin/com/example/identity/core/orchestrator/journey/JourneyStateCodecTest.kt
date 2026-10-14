package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journey.state.WebSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.strategy.ALL_INTENT_STRATEGIES
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
        RegisterState::class to setOf("Start", "Identifying", "DeviceLinkQuestion", "Assigning", "ConfirmingEmail", "SecondFactorKindObligation", "AuthChoice", "Enrolling"),
        RegisterEnrollFirstState::class to setOf("EnrollFirstStart", "EnrollFirstAttestingEmail", "EnrollFirstEnrollingSms", "EnrollFirstEnrolling", "EnrollFirstConfirmingEmail", "EnrollFirstSecondFactorKindObligation", "EnrollFirstConfirmDeviceRebind"),
        LookupLoginState::class to setOf("Start", "Credential", "AdditionalFactor", "DeviceLinkQuestion"),
        WebSelectMethodState::class to setOf("SelectMethod", "AfterIdentification"),
        StepUpState::class to setOf("Start", "AuthChoice"),
        ManageAuthMethodsState::class to setOf("AddRequested", "RemoveRequested", "Enrolling", "RetractAttributeRequested", "ChangeRequested", "Changing"),
        ConfirmPeerLoginState::class to setOf("Requested", "Confirming"),
        DeleteAccountState::class to setOf("ConfirmPending"),
        FreshProofState::class to setOf("Start", "Reproving"),
        ReIdentifyState::class to setOf("OfferReIdent", "Identifying"),
    )

    val codec = JourneyStateCodec(ALL_INTENT_STRATEGIES)

    given("the strategies the codec reads with") {
        then("there is one for every intent") {
            ALL_INTENT_STRATEGIES.map { it.intent }.toSet() shouldBe AuthIntent.entries.toSet()
        }
        then("their states' type names are exactly the persisted ones") {
            ALL_INTENT_STRATEGIES.flatMap { it.stateRoots }
                .associateWith { root -> JourneyStateCodec.concreteStates(root).map { it.simpleName }.toSet() } shouldBe persistedNames
        }
    }

    given("every state without fields") {
        then("it survives a write and a read under its own intent") {
            ALL_INTENT_STRATEGIES.forEach { strategy ->
                strategy.stateRoots.flatMap(JourneyStateCodec::concreteStates).mapNotNull { it.objectInstance }.forEach { state ->
                    val journey = AuthJourney(channelSessionId = ChannelSessionId(UUID.randomUUID()), intent = strategy.intent, createdAt = Instant.now())
                    codec.write(journey, state)
                    journey.stateType shouldBe state.javaClass.simpleName
                    codec.read(journey) shouldBe state
                }
            }
        }
    }
})
