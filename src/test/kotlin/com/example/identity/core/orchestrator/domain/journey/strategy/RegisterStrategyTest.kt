package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.policy.fromNow
import com.example.identity.core.orchestrator.domain.journey.strategy.AuthEnrollCore
import com.example.identity.core.orchestrator.domain.journey.strategy.FastAccessStrategy
import com.example.identity.core.orchestrator.domain.journey.strategy.RegisterStrategy
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
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
 * Unit coverage of [RegisterStrategy]'s journey: fresh identification even on a linked device
 * (docs/04-orchestrierung.md, "REGISTER"), through to the mandatory states that keep the next
 * login working. [AuthChoice]/[Enrolling] are shared with [FastAccessStrategy], but this strategy's
 * `transition` owns its own transitions around them, so they are covered here in full.
 */
class RegisterStrategyTest : BehaviorSpec({

    val strategy = RegisterStrategy()

    given("the intent") {
        then("is REGISTER") {
            strategy.intent shouldBe AuthIntent.REGISTER
        }
    }

    given("Start, even with a linked device credential and other active methods") {
        val acc = account(method("sms", AcrLevel.LOA2))

        `when`("the journey starts (Started)") {
            val transition = strategy.transition(RegisterState.Start, JourneyEvent.Started, ctx(account = acc))
            then("always goes straight to identification - no PreferredAuth/AuthChoice shortcut exists here") {
                transition shouldBe
                    Transition.To(RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect")))))
            }
        }
    }

    given("Start, no account known yet") {
        `when`("the journey starts (Started)") {
            val transition = strategy.transition(RegisterState.Start, JourneyEvent.Started, ctx(account = null))
            then("goes to identification, same as a run seeded by FAST_ACCESS's own sub-journey hand-off") {
                transition shouldBe
                    Transition.To(RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect")))))
            }
        }

        `when`("a RE_IDENTIFY sub-journey was declined (SubJourneyCancelled)") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
            val transition = strategy.transition(RegisterState.Start, event, ctx())
            then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Start, the fresh evidence already reaches the floor") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA2)
            val transition = strategy.transition(RegisterState.Start, event, theCtx)
            then("re-checks satisfaction via afterProof instead of re-running identification") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Identifying, more than one offered candidate") {
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"))))

        `when`("one is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(IdentFscDescriptor), ctx())
            then("keeps the choice among the rest") {
                transition shouldBe
                    Transition.To(state.withOffer(state.offer.copy(declined = setOf(ToolId("ident-fsc")))))
            }
        }
    }

    given("Identifying, one offered candidate") {
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(IdentFscDescriptor), ctx())
            then("gives up - the whole journey cancels, this is not an error") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Identifying, the newly (re-)found account can already reach the floor with an existing method") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, acrFloor = AcrLevel.LOA1)
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("a fresh identity was just established (Completed)") {
            val outcome = ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))
            val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the identity") {
                transition shouldBe
                    Transition.Perform(Action.RecordIdentification(IdentFscDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers it via the shared AuthChoice, rather than enrollment") {
                transition shouldBe
                    Transition.To(AuthChoice(Offer(listOf(ToolId("auth-sms")))))
            }
        }
    }

    given("Identifying, the account (brand new, or found without a sufficient method) needs to enroll something") {
        val acc = account(emailConfirmed = false)
        val theCtx = ctx(account = acc, acrFloor = AcrLevel.LOA1)
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))
        // Mirrors what JourneyService rebuilds after Action.RecordIdentification: IDENTITY-axis
        // evidence at loa2, which offerEnrollment needs before it offers Enrolling.
        val postIdentityCtx = ctx(
            account = acc,
            evidence = AuthEvidence.fromNow(
                listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to "loa2"),
                axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
            ),
            acrFloor = AcrLevel.LOA1
        )
        val confirmedCtx = ctx(
            account = account(emailConfirmed = true),
            evidence = AuthEvidence.fromNow(
                listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to "loa2"),
                axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
            ),
            acrFloor = AcrLevel.LOA1
        )

        `when`("a fresh identity was just established (Completed)") {
            val outcome = ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))
            val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the identity") {
                transition shouldBe
                    Transition.Perform(Action.RecordIdentification(IdentFscDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, postIdentityCtx)
            then("asks for the address FIRST - before any method is offered") {
                transition shouldBe
                    Transition.To(RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }

        `when`("resumed with the address already confirmed (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, confirmedCtx)
            then("offers enrollment once the address is confirmed - carrying no obligation forward") {
                transition.shouldBeInstanceOf<Transition.To>()
                val to = transition.state
                to.shouldBeInstanceOf<Enrolling>()
                to.emailObligation shouldBe true
                // confirm-email is not in here: attesting the address is its own act, discharged
                // above. enroll-password is a candidate because the confirmed address unlocks it.
                to.offered shouldContainExactlyInAnyOrder listOf(
                    ToolId("enroll-sms"), ToolId("enroll-device"), ToolId("enroll-kobil"), ToolId("enroll-qr"),
                    ToolId("enroll-email"), ToolId("enroll-password")
                )
            }
        }
    }

    given("Identifying, on a device already linked to a DIFFERENT account") {
        val acc = account(method("sms", AcrLevel.LOA1))
        // linkedAccountId (999L) differs from the newly identified account's own id - the
        // "Zweitaccount" conflict (docs/04-orchestrierung.md #2).
        val theCtx = ctx(account = acc, acrFloor = AcrLevel.LOA1, linkedAccountId = 999L)
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("a fresh identity was just established (Completed)") {
            val outcome = ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))
            val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the identity") {
                transition shouldBe
                    Transition.Perform(Action.RecordIdentification(IdentFscDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("asks for confirmation first, before offering any method - never silently rebinds") {
                transition shouldBe
                    Transition.To(RegisterState.ConfirmDeviceRebind)
            }
        }
    }

    given("ConfirmDeviceRebind") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val state = RegisterState.ConfirmDeviceRebind
        val conflicting = ctx(account = acc, acrFloor = AcrLevel.LOA1, linkedAccountId = 999L)
        val resolved = ctx(account = acc, acrFloor = AcrLevel.LOA1, linkedAccountId = acc.accountId)

        `when`("accepted") {
            val transition = strategy.transition(state, JourneyEvent.Answered("accept"), conflicting)
            then("links the device") {
                transition shouldBe
                    Transition.Perform(Action.LinkDevice, resumeState = state)
            }
        }

        `when`("resumed after linking the device (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, resolved)
            then("re-runs afterIdentification - now without a conflict") {
                transition shouldBe
                    Transition.To(AuthChoice(Offer(listOf(ToolId("auth-sms")))))
            }
        }

        `when`("declined") {
            val transition = strategy.transition(state, JourneyEvent.Answered("decline"), conflicting)
            then("cancels the journey outright - no silent continuation, the old binding is left untouched") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("AuthChoice, reached after identification rediscovers an already-equipped account, one candidate left") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"))))

        `when`("the last candidate is abandoned") {
            val transition = strategy.transition(
                state,
                JourneyEvent.Abandoned(AuthSmsDescriptor),
                ctx(account = acc, channel = ChannelType.KEYCLOAK)
            )
            then("falls back to identification again, not to enrollment - never wrapped with the factor-kind obligation") {
                transition shouldBe Transition.To(RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect")))))
            }
        }
    }

    given("ConfirmingEmail") {
        val state = RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(com.example.identity.tools.auth_email.ConfirmEmailDescriptor), ctx())
            then("re-offers the same full choice - the obligation itself is never waived by backing out") {
                transition shouldBe
                    Transition.To(state.withActive(null))
            }
        }
    }

    given("ConfirmingEmail on the APP channel, the account now reaches the floor") {
        val state = RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelType.APP, availableTools = StrategyTestFixtures.appTools
        )

        `when`("the email is confirmed (Completed)") {
            val outcome = ToolOutcome.Completed.Attested(
                claims = listOf(Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.of(ToolId("confirm-email"))))
            )
            val event = JourneyEvent.Completed(com.example.identity.tools.auth_email.ConfirmEmailDescriptor, outcome)
            // No credential and no device binding - the account keeps the address, nothing
            // was created that this device could later be recognized by.
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the attestation") {
                transition shouldBe
                    Transition.Perform(Action.AdoptAttestation(com.example.identity.tools.auth_email.ConfirmEmailDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the attestation (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("asks for a method of another factor kind") {
                transition shouldBe
                    Transition.To(RegisterState.SecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS)))
            }
        }
    }

    given("Enrolling") {
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))), emailObligation = true)

        `when`("abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsDescriptor), ctx())
            then("re-offers the same full choice, the tool just backed out of included") {
                transition shouldBe Transition.To(state.withActive(null))
            }
        }
    }

    given("Enrolling, floor reached, but the email obligation from Identifying is still open") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = true)

        `when`("a method was just enrolled (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("moves on to ConfirmingEmail instead of finishing") {
                transition shouldBe
                    Transition.To(RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    // The third obligation (docs/04-orchestrierung.md #8): a registration must leave the account
    // able to reach loa2, so a single factor kind gets a method of another kind added.
    given("Enrolling on the KEYCLOAK channel, sufficient, email already confirmed, sms only") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.KEYCLOAK,
            availableTools = StrategyTestFixtures.webTools
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))), emailObligation = false)

        `when`("sms is enrolled (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers only the password - the Web channel renders no device binding") {
                transition shouldBe
                    Transition.To(RegisterState.SecondFactorKindObligation(Offer(listOf(ToolId("enroll-password")))))
            }
        }
    }

    given("Enrolling on the KEYCLOAK channel, sufficient, but the email obligation is still open") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.KEYCLOAK,
            availableTools = StrategyTestFixtures.webTools
        )
        // enroll-password is no candidate without a confirmed email (docs/03-tool-architektur.md #1).
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = true)

        `when`("sms is enrolled (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("ConfirmingEmail comes first - the factor-kind obligation is only considered once the email is confirmed") {
                transition shouldBe
                    Transition.To(RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    given("Enrolling on the KEYCLOAK channel, email already confirmed, password only") {
        val acc = account(method("password", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("password"), setOf(FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.KEYCLOAK,
            availableTools = StrategyTestFixtures.webTools
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))), emailObligation = false)

        `when`("the user chose enroll-password directly (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
            val event = JourneyEvent.Completed(EnrollPasswordDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(EnrollPasswordDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("a password alone is one factor kind too - the possession factor is asked for") {
                transition shouldBe
                    Transition.To(RegisterState.SecondFactorKindObligation(Offer(listOf(ToolId("enroll-sms")))))
            }
        }
    }

    // A device credential declares POSSESSION+KNOWLEDGE+INHERENCE on its own.
    given("Enrolling, the account already holds a device credential, loa2 is reachable with it") {
        val acc = account(method("device", AcrLevel.LOA2), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.APP,
            availableTools = StrategyTestFixtures.appTools
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false)

        `when`("sms is enrolled (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("no further method is demanded") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Enrolling, the account already holds a device credential, loa2 is out of reach, capped by enrolledUnderAcr") {
        val acc = account(method("device", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.APP,
            availableTools = StrategyTestFixtures.appTools
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-device"))), emailObligation = false)

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("still no obligation - the account already covers more than one factor kind") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Enrolling on the APP channel, sms only") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.APP,
            availableTools = StrategyTestFixtures.appTools
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))), emailObligation = false)

        `when`("sms is enrolled (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers password, device binding and KOBIL - every method adding a missing factor kind") {
                transition shouldBe
                    Transition.To(RegisterState.SecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS)))
            }
        }
    }

    given("Enrolling, sms only, on a channel that can also log in by email code") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.APP,
            availableTools = StrategyTestFixtures.appTools + ToolId("auth-email")
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false)

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("the email login joins the offer - it adds knowledge and the channel can prove it") {
                val next = transition.shouldBeInstanceOf<Transition.To>().state
                next.shouldBeInstanceOf<RegisterState.SecondFactorKindObligation>().offer.offered shouldContainExactlyInAnyOrder
                    APP_SECOND_FACTOR_KINDS + ToolId("enroll-email")
            }
        }
    }

    given("Enrolling on KEYCLOAK, but the Web theme never declared enroll-password renderable") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.KEYCLOAK,
            availableTools = StrategyTestFixtures.webTools - ToolId("enroll-password")
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false)

        `when`("sms is enrolled (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("the obligation can't be enforced without a renderer - finishes directly instead of dead-ending") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("ConfirmingEmail on the KEYCLOAK channel, sms only") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.KEYCLOAK,
            availableTools = StrategyTestFixtures.webTools
        )
        val state = RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("the email is confirmed (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(com.example.identity.tools.auth_email.ConfirmEmailDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(com.example.identity.tools.auth_email.ConfirmEmailDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("the discharged email obligation falls through to the still-open factor-kind obligation, not straight to Authenticated") {
                transition shouldBe
                    Transition.To(RegisterState.SecondFactorKindObligation(Offer(listOf(ToolId("enroll-password")))))
            }
        }
    }

    given("SecondFactorKindObligation") {
        val state = RegisterState.SecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS))

        `when`("abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(EnrollPasswordDescriptor), ctx(availableTools = StrategyTestFixtures.appTools))
            then("re-offers the same full choice - the obligation itself is never waived by backing out") {
                transition shouldBe
                    Transition.To(state.withActive(null))
            }
        }
    }

    // Both methods sit at loa1, so loa2 stays out of reach: the obligation must still end.
    given("SecondFactorKindObligation on the APP channel, sms and password at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.APP,
            availableTools = StrategyTestFixtures.appTools
        )
        val state = RegisterState.SecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS))

        `when`("fulfilled with a password (Completed)") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
            val event = JourneyEvent.Completed(EnrollPasswordDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(EnrollPasswordDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes - no second round for the device binding") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("SecondFactorKindObligation on the APP channel, sms and device binding at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), method("device", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms", "device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.APP,
            availableTools = StrategyTestFixtures.appTools
        )
        val state = RegisterState.SecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS))

        `when`("resumed after fulfilling it with a device binding (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes - no password is demanded on top") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})

/** What an sms-only account is offered on the App channel, in catalog order. */
internal val APP_SECOND_FACTOR_KINDS = listOf(ToolId("enroll-password"), ToolId("enroll-device"), ToolId("enroll-kobil"))
