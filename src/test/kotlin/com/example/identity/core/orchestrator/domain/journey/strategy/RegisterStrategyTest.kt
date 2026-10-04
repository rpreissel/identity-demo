package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.policy.fromNow
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.APP_SECOND_FACTOR_KINDS
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.emailAttestation
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.identifiedOutcome
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit coverage of [RegisterStrategy]'s journey: fresh identification even on a linked device
 * (docs/04-orchestrierung.md, "REGISTER"), through to the mandatory states that keep the next
 * login working. [AuthChoice]/[Enrolling] are shared with [FastAccessStrategy], but this strategy's
 * `transition` owns its own transitions around them, so they are covered here in full. Which
 * action a completed tool yields is [AuthEnrollCoreTest]'s subject.
 */
class RegisterStrategyTest : BehaviorSpec({

    val strategy = RegisterStrategy()

    val smsProof = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
    val smsEnrollment = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
    val passwordEnrollment = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))

    // Every offering state hands a completed tool to AuthEnrollCore.proofAction and resumes where it was.
    listOf(
        RegisterCompletion(
            RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc")))),
            JourneyEvent.Completed(tool("ident-fsc"), identifiedOutcome()),
            Action.RecordIdentification(tool("ident-fsc"), identifiedOutcome())
        ),
        RegisterCompletion(
            AuthChoice(Offer(listOf(ToolId("auth-sms")))),
            JourneyEvent.Completed(tool("auth-sms"), smsProof),
            Action.AcceptProof(tool("auth-sms"), smsProof)
        ),
        RegisterCompletion(
            RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email")))),
            JourneyEvent.Completed(tool("confirm-email"), emailAttestation()),
            Action.AdoptAttestation(tool("confirm-email"), emailAttestation())
        ),
        RegisterCompletion(
            Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = true),
            JourneyEvent.Completed(tool("enroll-sms"), smsEnrollment),
            Action.AdoptCredential(tool("enroll-sms"), smsEnrollment)
        ),
        RegisterCompletion(
            RegisterState.SecondFactorKindObligation(Offer(APP_SECOND_FACTOR_KINDS)),
            JourneyEvent.Completed(tool("enroll-password"), passwordEnrollment),
            Action.AdoptCredential(tool("enroll-password"), passwordEnrollment)
        )
    ).forEach { (state, event, action) ->
        given("${state::class.simpleName}, its offered tool about to complete") {
            `when`("the tool completes") {
                val transition = strategy.transition(state, event, ctx())
                then("performs ${action::class.simpleName} and resumes in the same state") {
                    transition shouldBe Transition.Perform(action, resumeState = state)
                }
            }
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
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("ident-fsc")), ctx())
            then("keeps the choice among the rest") {
                transition shouldBe
                    Transition.To(state.withOffer(state.offer.copy(declined = setOf(ToolId("ident-fsc")))))
            }
        }
    }

    given("Identifying, one offered candidate") {
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("ident-fsc")), ctx())
            then("gives up - the whole journey cancels, this is not an error") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Identifying, the newly (re-)found account can already reach the floor with an existing method") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, acrFloor = AcrLevel.LOA1)
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers it via the shared AuthChoice, rather than enrollment") {
                transition shouldBe
                    Transition.To(AuthChoice(Offer(listOf(ToolId("auth-sms")))))
            }
        }
    }

    // Mirrors what JourneyService rebuilds after Action.RecordIdentification: IDENTITY-axis
    // evidence at loa2, which offerEnrollment needs before it offers Enrolling.
    val identityEvidence = SessionEvidence.fromNow(
        listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to "loa2"),
        axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
    )

    given("Identifying, the identified account has no method yet and no confirmed address") {
        val theCtx = ctx(account = account(emailConfirmed = false), evidence = identityEvidence, acrFloor = AcrLevel.LOA1)
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("asks for the address FIRST - before any method is offered") {
                transition shouldBe
                    Transition.To(RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    given("Identifying, the identified account has no method yet but a confirmed address") {
        val theCtx = ctx(account = account(emailConfirmed = true), evidence = identityEvidence, acrFloor = AcrLevel.LOA1)
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers enrollment and keeps the email obligation for the check after enrollment") {
                val to = transition.shouldBeInstanceOf<Transition.To>().state
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
        val theCtx = ctx(account = acc, acrFloor = AcrLevel.LOA1, linkedAccountId = AccountId(999L))
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("asks for confirmation first, before offering any method - never silently rebinds") {
                transition shouldBe
                    Transition.To(RegisterState.ConfirmDeviceRebind)
            }
        }
    }

    given("ConfirmDeviceRebind, the device still linked to another account") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val state = RegisterState.ConfirmDeviceRebind
        val conflicting = ctx(account = acc, acrFloor = AcrLevel.LOA1, linkedAccountId = AccountId(999L))

        `when`("accepted") {
            val transition = strategy.transition(state, JourneyEvent.Answered(ANSWER_ACCEPT), conflicting)
            then("links the device") {
                transition shouldBe
                    Transition.Perform(Action.LinkDevice, resumeState = state)
            }
        }

        `when`("declined") {
            val transition = strategy.transition(state, JourneyEvent.Answered(ANSWER_DECLINE), conflicting)
            then("cancels the journey outright - no silent continuation, the old binding is left untouched") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("ConfirmDeviceRebind, the device now linked to this account") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val resolved = ctx(account = acc, acrFloor = AcrLevel.LOA1, linkedAccountId = acc.accountId)

        `when`("resumed after linking the device (ActionCompleted)") {
            val transition = strategy.transition(RegisterState.ConfirmDeviceRebind, JourneyEvent.ActionCompleted, resolved)
            then("re-runs afterIdentification - now without a conflict") {
                transition shouldBe
                    Transition.To(AuthChoice(Offer(listOf(ToolId("auth-sms")))))
            }
        }
    }

    given("Identifying, an attested person not yet bound in the register, no method yet") {
        val unbound = account(personId = null, attestedIdentity = true)
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-eid"))))

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, ctx(account = unbound, evidence = identityEvidence))

            then("offers the assignment to the register before any enrollment (ADR-18)") {
                transition shouldBe Transition.To(RegisterState.Assigning(Offer(listOf(ToolId("ident-kvnr")))))
            }
        }
    }

    given("Identifying, a person not yet bound in the register but without an attested identity") {
        val unattested = account(personId = null)
        val state = RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"))))

        `when`("resumed after recording the identity (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, ctx(account = unattested, evidence = identityEvidence))

            then("skips the assignment, since ident-kvnr has nothing to match against, and offers enrollment") {
                val enrolling = transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<Enrolling>()
                enrolling.emailObligation shouldBe true
            }
        }
    }

    given("Assigning, an attested person not yet bound in the register") {
        val unbound = account(personId = null, attestedIdentity = true)
        val theCtx = ctx(account = unbound, evidence = identityEvidence)
        val state = RegisterState.Assigning(Offer(listOf(ToolId("ident-kvnr"))))

        `when`("the assignment is backed out of (\"not now\")") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("ident-kvnr")), theCtx)

            then("the registration carries on unbound and offers enrollment (ADR-10)") {
                val enrolling = transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<Enrolling>()
                enrolling.emailObligation shouldBe true
            }
        }

        `when`("ident-kvnr finds the person in the register") {
            val found = identifiedOutcome()
            val transition = strategy.transition(state, JourneyEvent.Completed(tool("ident-kvnr"), found), theCtx)

            then("records the identification and resumes here") {
                transition shouldBe Transition.Perform(Action.RecordIdentification(tool("ident-kvnr"), found), resumeState = state)
            }
        }

        `when`("a tool completes with anything but an identification") {
            val enrolled = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("kvnr", "ref"))
            val result = runCatching { strategy.transition(state, JourneyEvent.Completed(tool("ident-kvnr"), enrolled), theCtx) }

            then("fails loudly - the assignment step offers nothing else") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("Assigning, the person now bound in the register") {
        val bound = account(attestedIdentity = true)
        val state = RegisterState.Assigning(Offer(listOf(ToolId("ident-kvnr"))))

        `when`("resumed after recording the identification (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, ctx(account = bound, evidence = identityEvidence))

            then("continues with enrollment and does not offer the assignment again") {
                val enrolling = transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<Enrolling>()
                enrolling.emailObligation shouldBe true
            }
        }
    }

    given("Assigning, the assignment moved the run to an account this device is not linked to") {
        val other = account(accountId = AccountId(2L), attestedIdentity = true)
        val theCtx = ctx(account = other, evidence = identityEvidence, linkedAccountId = AccountId(1L))
        val state = RegisterState.Assigning(Offer(listOf(ToolId("ident-kvnr"))))

        `when`("resumed after recording the identification (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)

            then("asks before rebinding the device, the same check as after identification (ADR-20)") {
                transition shouldBe Transition.To(RegisterState.ConfirmDeviceRebind)
            }
        }
    }

    given("AuthChoice, reached after identification rediscovers an already-equipped account, one candidate left") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"))))

        `when`("the last candidate is abandoned") {
            val transition = strategy.transition(
                state,
                JourneyEvent.Abandoned(tool("auth-sms")),
                ctx(account = acc, channel = ChannelType.WEB)
            )
            then("falls back to identification again, not to enrollment - never wrapped with the factor-kind obligation") {
                transition shouldBe Transition.To(RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect")))))
            }
        }
    }

    given("ConfirmingEmail") {
        val state = RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("confirm-email")), ctx())
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
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("auth-sms")), ctx())
            then("re-offers the same full choice, the tool just backed out of included") {
                transition shouldBe Transition.To(state.withActive(null))
            }
        }
    }

    // ENROLLMENT_FLOOR_ACR (docs/journeys/register.md): below loa2 a new method needs a fresh identification first.
    given("Enrolling, the session still below loa2 and enrollment tools available") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val state = Enrolling(Offer(listOf(ToolId("enroll-password"))), emailObligation = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)

        `when`("resumed after an adopted credential that leaves the floor unmet (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("requires RE_IDENTIFY instead of offering the next enrollment, resuming at Start") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.RE_IDENTIFY,
                        seedWith = ReIdentifyState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                        resumeWith = RegisterState.Start
                    )
            }
        }
    }

    given("Enrolling, floor reached, but the email obligation from Identifying is still open") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = true)

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("moves on to ConfirmingEmail instead of finishing") {
                transition shouldBe
                    Transition.To(RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    // The third obligation (docs/04-orchestrierung.md #5): a registration must leave the account
    // able to reach loa2, so a single factor kind gets a method of another kind added.
    given("Enrolling on the WEB channel, sufficient, email already confirmed, sms only") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.WEB,
            availableTools = StrategyTestFixtures.webTools
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))), emailObligation = false)

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers only the password - the Web channel renders no device binding") {
                transition shouldBe
                    Transition.To(RegisterState.SecondFactorKindObligation(Offer(listOf(ToolId("enroll-password")))))
            }
        }
    }

    given("Enrolling on the WEB channel, sufficient, but the email obligation is still open") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.WEB,
            availableTools = StrategyTestFixtures.webTools
        )
        // enroll-password is no candidate without a confirmed email (docs/03-tool-architektur.md #5).
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = true)

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("ConfirmingEmail comes first - the factor-kind obligation is only considered once the email is confirmed") {
                transition shouldBe
                    Transition.To(RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
            }
        }
    }

    given("Enrolling on the WEB channel, email already confirmed, password only") {
        val acc = account(method("password", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("password"), setOf(FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.WEB,
            availableTools = StrategyTestFixtures.webTools
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))), emailObligation = false)

        `when`("resumed after adopting the password (ActionCompleted)") {
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

    given("Enrolling on WEB, but the Web theme never declared enroll-password renderable") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.WEB,
            availableTools = StrategyTestFixtures.webTools - ToolId("enroll-password")
        )
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false)

        `when`("resumed after adopting the credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("the obligation can't be enforced without a renderer - finishes directly instead of dead-ending") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("ConfirmingEmail on the WEB channel, sms only") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1,
            channel = ChannelType.WEB,
            availableTools = StrategyTestFixtures.webTools
        )
        val state = RegisterState.ConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))

        `when`("resumed after adopting the attestation (ActionCompleted)") {
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
            val transition = strategy.transition(state, JourneyEvent.Abandoned(tool("enroll-password")), ctx(availableTools = StrategyTestFixtures.appTools))
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

        `when`("resumed after fulfilling it with a password (ActionCompleted)") {
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

/** One row of the completed-tool table: the state, the completion it receives, the action it performs. */
private data class RegisterCompletion(val state: RegisterState, val event: JourneyEvent.Completed, val action: Action)
