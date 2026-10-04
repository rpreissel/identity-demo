package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journey.state.WebSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.state.LogoutState
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.AuthIntent
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
        RegisterState::class to setOf("Start", "Identifying", "ConfirmDeviceRebind", "Assigning", "ConfirmingEmail", "SecondFactorKindObligation", "AuthChoice", "Enrolling"),
        RegisterEnrollFirstState::class to setOf("EnrollFirstStart", "EnrollFirstAttestingEmail", "EnrollFirstEnrollingSms", "EnrollFirstEnrolling", "EnrollFirstConfirmingEmail", "EnrollFirstSecondFactorKindObligation", "EnrollFirstConfirmDeviceRebind"),
        LookupLoginState::class to setOf("Start", "Credential", "AdditionalFactor", "OfferBinding", "ConfirmDeviceRebind"),
        WebSelectMethodState::class to setOf("SelectMethod"),
        StepUpState::class to setOf("Start", "AuthChoice"),
        ManageAuthMethodsState::class to setOf("AddRequested", "RemoveRequested", "Enrolling", "RetractAttributeRequested", "ConfirmationRequired"),
        ConfirmPeerLoginState::class to setOf("Requested", "ConfirmationRequired", "Confirming", "OfferLogout"),
        DeleteAccountState::class to setOf("ConfirmPending", "ConfirmationRequired"),
        LogoutState::class to setOf("ConfirmPending"),
        ReIdentifyState::class to setOf("OfferReIdent", "Identifying"),
    )

    given("every sealed root a journey is read back as") {
        then("its states' type names are exactly the persisted ones") {
            JourneyStateCodec.STATE_ROOTS.associateWith { root -> JourneyStateCodec.concreteStates(root).map { it.simpleName }.toSet() } shouldBe persistedNames
        }
    }

    given("every state without fields") {
        then("it survives a write and a read under its own intent") {
            val codec = JourneyStateCodec()
            val intentOf = mapOf(
                FastAccessState::class to AuthIntent.FAST_ACCESS, RegisterState::class to AuthIntent.REGISTER,
                RegisterEnrollFirstState::class to AuthIntent.REGISTER, LookupLoginState::class to AuthIntent.LOOKUP_LOGIN,
                WebSelectMethodState::class to AuthIntent.WEB_SELECT_METHOD, StepUpState::class to AuthIntent.STEP_UP,
                ManageAuthMethodsState::class to AuthIntent.MANAGE_AUTH_METHODS, ConfirmPeerLoginState::class to AuthIntent.CONFIRM_PEER_LOGIN,
                DeleteAccountState::class to AuthIntent.DELETE_ACCOUNT, LogoutState::class to AuthIntent.LOGOUT,
                ReIdentifyState::class to AuthIntent.RE_IDENTIFY,
            )
            JourneyStateCodec.STATE_ROOTS.forEach { root ->
                JourneyStateCodec.concreteStates(root).mapNotNull { it.objectInstance }.forEach { state ->
                    val journey = AuthJourney(channelSessionId = ChannelSessionId(UUID.randomUUID()), intent = intentOf.getValue(root), createdAt = Instant.now())
                    codec.write(journey, state)
                    journey.stateType shouldBe state.javaClass.simpleName
                    codec.read(journey) shouldBe state
                }
            }
        }
    }
})
