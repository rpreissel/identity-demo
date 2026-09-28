package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.strategy.ManageAuthMethodsStrategy
import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [ManageAuthMethodsStrategy] - add/remove authentication methods on an
 * already-authenticated channel (docs/04-orchestrierung.md, "MANAGE_AUTH_METHODS"). No Spring
 * context, no HTTP.
 */
class ManageAuthMethodsStrategyTest : BehaviorSpec({

    val strategy = ManageAuthMethodsStrategy()

    given("the intent") {
        then("is MANAGE_AUTH_METHODS") {
            strategy.intent shouldBe AuthIntent.MANAGE_AUTH_METHODS
        }
    }

    given("initialState") {
        then("is AddRequested") {
            strategy.initialState(ctx()) shouldBe ManageAuthMethodsState.AddRequested
        }
    }

    given("Enrolling, a single candidate offered") {
        val state = ManageAuthMethodsState.Enrolling(Offer(listOf(ToolId("enroll-sms"))))

        `when`("a tool completes Enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("binds the device - it's already known, so this is a harmless no-op that keeps it reachable") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("a tool completes Identified") {
            val result = runCatching {
                strategy.transition(state, JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))), ctx())
            }
            then("Identified is not offered by this intent") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("a tool completes Authenticated") {
            val result = runCatching {
                strategy.transition(state, JourneyEvent.Completed(AuthSmsDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))), ctx())
            }
            then("Authenticated is not offered by this intent") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("AddRequested, the session does not yet carry loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("started") {
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            then("parks the wish and demands a step-up first, without losing it") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.STEP_UP,
                        seedWith = StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                        resumeWith = ManageAuthMethodsState.AddRequested
                    )
            }
        }

        `when`("resumed after the gate's own STEP_UP was declined (SubJourneyCancelled)") {
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP), theCtx)
            then("gives up on the wish rather than re-requesting the identical STEP_UP again") {
                transition shouldBe
                    Transition.Cancel
            }
        }
    }

    given("AddRequested, a never-identified account (personId == null) sitting at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("started") {
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            then("selfServiceAcrFloor only demands loa1 for this account - offers enrollment candidates directly, no step-up") {
                transition.shouldBeInstanceOf<Transition.To>()
            }
        }
    }

    given("RemoveRequested, a never-identified account (personId == null) sitting at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        `when`("started") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            then("selfServiceAcrFloor only demands loa1 for this account - removes the method directly, no step-up") {
                transition shouldBe
                    Transition.Perform(Action.RevokeAuthMethod("sms-instance"), resumeState = state)
            }
        }
    }

    given("AddRequested, the session already carries loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)

        `when`("started") {
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            then("offers enrollment candidates directly") {
                transition.shouldBeInstanceOf<Transition.To>()
            }
        }
    }

    given("AddRequested, loa2 satisfied but nothing left to enroll") {
        val acc = account(
            method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2), method("email", AcrLevel.LOA2),
            method("device", AcrLevel.LOA2), method("kobil", AcrLevel.LOA2), method("qr", AcrLevel.LOA2)
        )
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        // device and kobil (allowsMultipleInstances) are deliberately still offered even with
        // one active instance, so this case is only reachable by ALSO backend-disabling them.
        val disabled = theCtx.availableTools - ToolId("enroll-device") - ToolId("enroll-kobil")

        `when`("started") {
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx.copy(availableTools = disabled))
            then("finishes - not an error, just nothing more to add (the device-bound methods stay offered since they allow multiple instances, so this really only fires once every singleton method is active)") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("RemoveRequested, the session does not yet carry loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        `when`("started") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            then("parks the wish and demands a step-up first") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.STEP_UP,
                        seedWith = StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                        resumeWith = state
                    )
            }
        }

        `when`("resumed after the gate's own STEP_UP was declined (SubJourneyCancelled)") {
            val transition = strategy.transition(state, JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP), theCtx)
            then("gives up on the wish rather than re-requesting the identical STEP_UP again") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("RemoveRequested, the session already carries loa2") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        `when`("started") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            then("removes the method directly - the machine, not this strategy, rejects self-lockout") {
                transition shouldBe
                    Transition.Perform(Action.RevokeAuthMethod("sms-instance"), resumeState = state)
            }
        }

        `when`("resumed after the removal (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Enrolling, several candidates offered") {
        val state = ManageAuthMethodsState.Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))))

        `when`("a tool is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsDescriptor), ctx())
            then("stays in Enrolling with the full choice back - not a decline, just picking differently") {
                transition shouldBe
                    Transition.To(state.withActive(null))
            }
        }

        `when`("a method is enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, ctx())
            then("finishes - one successful enrollment is always enough here") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})
