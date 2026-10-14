package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.APP_SECOND_FACTOR_KINDS
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit coverage of [RegisterEnrollFirstStrategy], the "Enrollment zuerst" experiment
 * (docs/04-orchestrierung.md, REGISTER). It is independent of [RegisterStrategy] (see
 * [RegisterEnrollFirstState]), so its transition function is covered in full, as
 * [RegisterStrategyTest] does for the ident-first journey.
 */
class RegisterEnrollFirstStrategyTest : BehaviorSpec({

    val strategy = RegisterEnrollFirstStrategy()

    // Mirrors the literal RegisterEnrollFirstStrategy.offerIdentificationOrFinish builds: its closing
    // offer needs its own wording (never identified before), not RE_IDENTIFY's shared default text.
    val optionalIdentification = Transition.RequireSubJourney(
        AuthIntent.RE_IDENTIFY,
        seedWith = ReIdentifyState.forSubJourney(
            targetAcr = AcrLevel.LOA1,
            wording = ReIdentifyState.Wording.OPTIONAL_IDENTIFICATION
        ),
        resumeWith = RegisterEnrollFirstState.EnrollFirstStart,
        // Declining it finishes the registration instead of cancelling it.
        optional = true,
    )

    given("Start, no account known yet") {
        `when`("started") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, ctx(account = null))
            then("offers EMAIL enrollment first, mandatory - not the whole menu, no identification step yet") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    given("Start, email attestation tool unavailable right now (admin-disabled)") {
        val theCtx = ctx(account = null, availableTools = StrategyTestFixtures.allToolIds - ToolId("confirm-email"))

        `when`("started") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, theCtx)
            then("skips to the free choice of a method instead of blocking or crashing on the missing account") {
                val next = transition.shouldBeInstanceOf<Transition.To>().state
                next.shouldBeInstanceOf<RegisterEnrollFirstState.EnrollFirstEnrolling>().offered shouldContainExactlyInAnyOrder
                    listOf(ToolId("enroll-sms"), ToolId("enroll-device"), ToolId("enroll-kobil"), ToolId("enroll-qr"))
            }
        }
    }

    given("EnrollFirstAttestingEmail, no account created yet") {
        val state = RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("resumed after adopting the attestation (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, ctx(account = null))
            then("moves on to the free choice of a method - no method is prescribed") {
                val next = transition.shouldBeInstanceOf<Transition.To>().state
                next.shouldBeInstanceOf<RegisterEnrollFirstState.EnrollFirstEnrolling>().offered shouldContain ToolId("enroll-sms")
            }
        }
    }

    given("Start, waiting on the closing, optional RE_IDENTIFY sub-journey") {
        `when`("resumed after it was accepted and succeeded (SubJourneyFinished)") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA2), ctx())
            then("finishes") {
                transition shouldBe
                    Transition.Authenticated
            }
        }

        `when`("resumed after it was declined or abandoned (SubJourneyCancelled)") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY, optional = true), ctx())
            then("finishes all the same") {
                transition shouldBe
                    Transition.Authenticated
            }
        }
    }

    given("Enrolling, floor reached, but email is still unconfirmed") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(listOf(ToolId("enroll-sms"))))

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("moves on to ConfirmingEmail - email is always obligatory here") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    given("ConfirmingEmail, on the APP channel - the factor-kind obligation applies here too") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelType.APP, availableTools = StrategyTestFixtures.appTools
        )
        val state = RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("resumed after attesting (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("password, device binding and KOBIL are offered") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstSecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS)))
            }
        }
    }

    given("ConfirmingEmail, on the WEB channel, sms only") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelType.WEB, availableTools = StrategyTestFixtures.webTools
        )
        val state = RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("resumed after attesting (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("falls through to the still-open factor-kind obligation with only the password, not to the identification offer yet") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstSecondFactorKindObligation(Offer(listOf(ToolId("enroll-password")))))
            }
        }
    }

    // Without identification loa2 is never reachable (enrolledUnderAcr caps at loa1), so the
    // obligation must end on the factor kinds alone.
    given("SecondFactorKindObligation, fulfilled with a password - every obligation now discharged") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelType.APP, availableTools = StrategyTestFixtures.appTools
        )
        val state = RegisterEnrollFirstState.EnrollFirstSecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS))

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers the optional identification step - no second round for the device binding") {
                transition shouldBe optionalIdentification
            }
        }
    }

    given("SecondFactorKindObligation, fulfilled with a device binding - every obligation now discharged") {
        val acc = account(method("sms", AcrLevel.LOA1), method("device", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms", "device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelType.APP, availableTools = StrategyTestFixtures.appTools
        )
        val state = RegisterEnrollFirstState.EnrollFirstSecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS))

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers the optional identification step - no password on top") {
                transition shouldBe optionalIdentification
            }
        }
    }
})

/** One row of the completed-tool table: the state, the completion it receives, the action it performs. */
private data class EnrollFirstCompletion(val state: RegisterEnrollFirstState, val event: JourneyEvent.Completed, val action: Action)
