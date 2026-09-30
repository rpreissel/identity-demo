package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.strategy.AuthEnrollCore
import com.example.identity.core.orchestrator.domain.journey.strategy.RegisterEnrollFirstStrategy
import com.example.identity.core.orchestrator.domain.journey.strategy.RegisterStrategy
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.tools.auth_email.ConfirmEmailDescriptor
import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.auth_sms.EnrollSmsDescriptor
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
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
    val enrollFirstIdentificationWording = ReIdentifyState.Wording.OPTIONAL_IDENTIFICATION

    given("the intent") {
        then("is REGISTER, same as the ident-first variant - only the dispatcher tells them apart") {
            strategy.intent shouldBe AuthIntent.REGISTER
        }
    }

    given("Start, no account known yet") {
        `when`("started") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, ctx(account = null))
            then("offers EMAIL enrollment first, mandatory - not the whole menu, no identification step yet") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    given("Start, email enrollment tool unavailable right now (admin-disabled)") {
        val theCtx = ctx(account = null, availableTools = StrategyTestFixtures.allToolIds - ToolId("confirm-email"))

        `when`("started") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, theCtx)
            then("skips straight to the mandatory SMS step instead of blocking the journey") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(listOf(ToolId("enroll-sms")))))
            }
        }
    }

    given("Start, neither email nor SMS enrollment tool available") {
        val theCtx = ctx(account = null, availableTools = StrategyTestFixtures.allToolIds - ToolId("confirm-email") - ToolId("enroll-sms"))

        `when`("started") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, theCtx)
            then("falls back to the old free-choice-among-everything offer instead of crashing on a missing account") {
                transition
                    .shouldBeEnrollingWith("enroll-device", "enroll-kobil", "enroll-qr")
            }
        }
    }

    given("EnrollFirstAttestingEmail, an account whose email is confirmed") {
        val acc = account(method("email", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("email"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("email is just enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(ConfirmEmailDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(ConfirmEmailDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("moves on to the mandatory SMS step - not to the free-choice menu") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(listOf(ToolId("enroll-sms")))))
            }
        }
    }

    given("EnrollFirstAttestingEmail, more than one offered candidate") {
        val state = RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("a tool is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(ConfirmEmailDescriptor), ctx())
            then("re-offers the same mandatory step, no skipping ahead to SMS") {
                transition shouldBe
                    Transition.To(state.withActive(null))
            }
        }
    }

    given("EnrollFirstEnrollingSms, floor reached, but email is still unconfirmed") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(listOf(ToolId("enroll-sms"))))

        `when`("sms is just enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("falls into the normal obligation cascade - email is always obligatory here") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    given("EnrollFirstEnrollingSms, more than one offered candidate") {
        val state = RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(listOf(ToolId("enroll-sms"))))

        `when`("a tool is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(EnrollSmsDescriptor), ctx())
            then("re-offers the same mandatory step") {
                transition shouldBe
                    Transition.To(state.withActive(null))
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
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY), ctx())
            then("finishes all the same") {
                transition shouldBe
                    Transition.Authenticated
            }
        }
    }

    given("Enrolling, more than one offered candidate") {
        val state = RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("confirm-email"))))

        `when`("a tool is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(EnrollSmsDescriptor), ctx())
            then("re-offers the FULL choice - this is a mandatory state, not a fallback") {
                transition shouldBe
                    Transition.To(state.withActive(null))
            }
        }
    }

    given("Enrolling, floor reached, but email is still unconfirmed") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(listOf(ToolId("enroll-sms"))))

        `when`("a method is just enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting (ActionCompleted)") {
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

        `when`("the email is confirmed (Attested)") {
            val outcome = ToolOutcome.Completed.Attested(
                claims = listOf(Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.of(ToolId("confirm-email"))))
            )
            val event = JourneyEvent.Completed(ConfirmEmailDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("the address is attested") {
                transition shouldBe
                    Transition.Perform(Action.AdoptAttestation(ConfirmEmailDescriptor, outcome), resumeState = state)
            }
        }

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

        `when`("the email is confirmed (Enrolled)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(ConfirmEmailDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(ConfirmEmailDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting (ActionCompleted)") {
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

        `when`("the password is just enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
            val event = JourneyEvent.Completed(EnrollPasswordDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(EnrollPasswordDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers the optional identification step - no second round for the device binding") {
                transition.shouldBeInstanceOf<Transition.RequireSubJourney>()
                transition.intent shouldBe AuthIntent.RE_IDENTIFY
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

        `when`("resumed after adopting (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers the optional identification step - no password on top") {
                transition.shouldBeInstanceOf<Transition.RequireSubJourney>()
            }
        }
    }
})

private fun Transition.shouldBeEnrollingWith(vararg toolIds: String) {
    require(this is Transition.To) { "expected Transition.To, was $this" }
    val to = state
    require(to is RegisterEnrollFirstState.EnrollFirstEnrolling) { "expected RegisterEnrollFirstState.EnrollFirstEnrolling, was $to" }
    to.offered shouldContainExactlyInAnyOrder toolIds.map { ToolId(it) }
}
