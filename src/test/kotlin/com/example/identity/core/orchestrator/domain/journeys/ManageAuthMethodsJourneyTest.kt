package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.state.ProofPurpose
import java.time.Duration
import com.example.identity.core.orchestrator.domain.policy.provenAt
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.domain.journeys.ManageAuthMethodsJourney
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [ManageAuthMethodsJourney] - add/remove authentication methods on an
 * already-authenticated channel (docs/04-orchestrierung.md, "MANAGE_AUTH_METHODS"). No Spring
 * context, no HTTP. Level and freshness are the FRESH_PROOF sub-journey's ([FreshProofJourneyTest]).
 */
class ManageAuthMethodsJourneyTest : BehaviorSpec({

    val journey = ManageAuthMethodsJourney

    // An identification proves loa2 on its own, whatever methods the account holds.
    val loa2Evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION))

    fun proofRequired(transition: Transition, requiredAcr: AcrLevel, resumeWith: ManageAuthMethodsState) {
        val required = transition.shouldBeInstanceOf<Transition.RequireSubJourney>()
        required.journey shouldBe FreshProofJourney
        required.resumeWith shouldBe resumeWith
        required.start shouldBe FreshProofJourney.before(ProofPurpose.MANAGE_METHODS, requiredAcr)
    }
    val proven = JourneyEvent.SubJourneyFinished(FreshProofJourney, achievedAcr = AcrLevel.LOA2)

    given("AddRequested, an identified account at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("started") {
            val transition = journey.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            then("parks the wish and requires loa2 plus a fresh proof, without losing it (ADR-61)") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        FreshProofJourney.before(ProofPurpose.MANAGE_METHODS, AcrLevel.LOA2),
                        resumeWith = ManageAuthMethodsState.AddRequested
                    )
            }
        }

        `when`("resumed after the proof is in (SubJourneyFinished)") {
            val transition = journey.transition(ManageAuthMethodsState.AddRequested, proven, theCtx)
            then("offers the enrollment candidates") {
                val next = transition.shouldBeInstanceOf<Transition.To>().state
                next.shouldBeInstanceOf<ManageAuthMethodsState.Enrolling>().offered shouldContainExactlyInAnyOrder ENROLLABLE_BESIDES_SMS
            }
        }
    }

    given("AddRequested, a never-identified account (personId == null)") {
        val acc = account(method("sms", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        `when`("started") {
            val transition = journey.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            then("selfServiceAcrFloor only demands loa1 for this account") {
                proofRequired(transition, AcrLevel.LOA1, ManageAuthMethodsState.AddRequested)
            }
        }
    }

    given("RemoveRequested, a never-identified account (personId == null)") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        `when`("started") {
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)
            then("requires loa1 plus a fresh proof, and removes nothing yet") {
                proofRequired(transition, AcrLevel.LOA1, state)
            }
        }

        `when`("resumed after the proof is in") {
            val transition = journey.transition(state, proven, theCtx)
            then("removes the method and resumes in the wish - the machine, not this journey, rejects self-lockout") {
                transition shouldBe Transition.Perform(Action.RevokeAuthMethod("sms-instance"), resumeState = state)
            }
        }

        `when`("resumed after the removal (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    // ADR-37: for an account without a person, the mailbox is the owner - withdrawing its address costs no step-up.
    given("RetractAttributeRequested for the email") {
        val state = ManageAuthMethodsState.RetractAttributeRequested(AttributeType.EMAIL)

        `when`("started on a never-identified account (personId == null)") {
            val acc = account(method("sms", AcrLevel.LOA1), personId = null)
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)
            then("requires only loa1") {
                proofRequired(transition, AcrLevel.LOA1, state)
            }
        }

        `when`("started on an identified account") {
            val acc = account(method("sms", AcrLevel.LOA1))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
            val transition = journey.transition(state, JourneyEvent.Started, theCtx)
            then("requires loa2") {
                proofRequired(transition, AcrLevel.LOA2, state)
            }
        }

        `when`("resumed after the proof is in") {
            val transition = journey.transition(state, proven, ctx(account = account(method("sms", AcrLevel.LOA1))))
            then("withdraws the address") {
                transition shouldBe Transition.Perform(Action.RetractAttribute(AttributeType.EMAIL), resumeState = state)
            }
        }
    }

    given("AddRequested, nothing left to enroll") {
        val acc = account(
            method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2), method("email", AcrLevel.LOA2),
            method("device", AcrLevel.LOA2), method("kobil", AcrLevel.LOA2), method("qr", AcrLevel.LOA2)
        )
        // device and kobil (allowsMultipleInstances) are deliberately still offered even with
        // one active instance, so this case is only reachable by ALSO backend-disabling them.
        val theCtx = ctx(
            account = acc, evidence = loa2Evidence.provenAt(TEST_NOW.minus(Duration.ofMinutes(6))), acrFloor = AcrLevel.LOA1,
            availableTools = JourneyTestFixtures.allToolIds - ToolId("enroll-device") - ToolId("enroll-kobil")
        )

        `when`("started, on an aged session") {
            val transition = journey.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            then("finishes without asking for any proof - there is nothing to authorize") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("ChangeRequested for the password, the session carries loa2") {
        val acc = account(method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2), method("device", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = loa2Evidence, acrFloor = AcrLevel.LOA1)
        val wish = ManageAuthMethodsState.ChangeRequested("password-instance")

        `when`("started") {
            val transition = journey.transition(wish, JourneyEvent.Started, theCtx)
            then("requires the level the old password was enrolled under plus a fresh proof") {
                proofRequired(transition, AcrLevel.LOA2, wish)
            }
        }

        `when`("resumed after the proof is in") {
            val transition = journey.transition(wish, proven, theCtx)
            then("offers exactly the password enrollment, though the method is already active") {
                val next = transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<ManageAuthMethodsState.Changing>()
                next.offered shouldBe listOf(ToolId("enroll-password"))
                next.methodInstanceId shouldBe "password-instance"
            }
        }

        `when`("the wish names a device instance") {
            val transition = journey.transition(ManageAuthMethodsState.ChangeRequested("device-instance"), JourneyEvent.Started, theCtx)
            then("aborts before asking for anything - a method with one credential per device is added or removed, never changed") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }

        `when`("the wish names an instance that is not active") {
            val transition = journey.transition(ManageAuthMethodsState.ChangeRequested("gone"), JourneyEvent.Started, theCtx)
            then("aborts") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    given("ChangeRequested for a password enrolled under loa2, a never-identified account sitting at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA2), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val wish = ManageAuthMethodsState.ChangeRequested("password-instance")

        `when`("started") {
            val transition = journey.transition(wish, JourneyEvent.Started, theCtx)
            then("demands the level the old password was enrolled under - a change never lowers it") {
                proofRequired(transition, AcrLevel.LOA2, wish)
            }
        }
    }

    given("Changing the password") {
        val state = ManageAuthMethodsState.Changing(Offer(listOf(ToolId("enroll-password"))), "password-instance")

        `when`("the tool is abandoned") {
            val exhausted = state.declining(ToolId("enroll-password"))
            val transition = journey.transition(exhausted as ManageAuthMethodsState, JourneyEvent.OfferExhausted(exhausted.declined), ctx(account = account(method("sms", AcrLevel.LOA2))))
            then("cancels - nothing else is offered, the old password stays") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("the new password is enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
            then("it is booked as the replacement of the old one, which it is never written below") {
                state.booking(tool("enroll-password"), outcome) shouldBe
                    Action.AdoptCredential(tool("enroll-password"), outcome, replaces = "password-instance")
            }
        }

        `when`("resumed after adopting (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, ctx(account = account(method("sms", AcrLevel.LOA2))))
            then("finishes") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Enrolling, several candidates offered") {
        val state = ManageAuthMethodsState.Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))))

        `when`("resumed after adopting (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, ctx(account = account(method("sms", AcrLevel.LOA2))))
            then("finishes - one successful enrollment is always enough here") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})

/** What an account holding only sms can still enroll, with every tool available. */
private val ENROLLABLE_BESIDES_SMS = listOf(
    ToolId("enroll-password"), ToolId("enroll-email"), ToolId("enroll-device"), ToolId("enroll-kobil"), ToolId("enroll-qr")
)
