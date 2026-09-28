package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.strategy.FastAccessStrategy
import com.example.identity.core.orchestrator.domain.journey.strategy.RegisterStrategy
import com.example.identity.tools.auth_device.AuthDeviceDescriptor
import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.deviceDetails
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit coverage of [FastAccessStrategy]: the fallback chain into a login, plus [Enrolling] for the
 * case it reaches without identifying anyone (docs/04-orchestrierung.md, "FAST_ACCESS").
 * Identification is [RegisterStrategy]'s journey, see [RegisterStrategyTest]. A completed tool
 * yields a [Transition.Perform], so tests assert both steps: the `Perform`, then
 * [JourneyEvent.ActionCompleted] against a context that reflects the action.
 */
class FastAccessStrategyTest : BehaviorSpec({

    val strategy = FastAccessStrategy()

    given("the intent") {
        then("is FAST_ACCESS") {
            strategy.intent shouldBe AuthIntent.FAST_ACCESS
        }
    }

    given("a completed tool, interpreted the same regardless of which offering state routed it here") {
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"))))

        `when`("an identification tool completes with Identified") {
            val outcome = ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))
            val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("Identified always finds-or-creates the account - brand new or found again by KVNR alike") {
                transition shouldBe
                    Transition.Perform(Action.RecordIdentification(IdentFscDescriptor, outcome), resumeState = state)
            }
        }

        `when`("an enrollment tool completes with Enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("Enrolled binds the device - a fresh credential on this device is worth remembering") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("an auth tool completes with Authenticated") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("Authenticated is accepted as proof, never adopting a different account, always binding the device") {
                transition shouldBe
                    Transition.Perform(Action.AcceptProof(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }
    }

    given("Start, no account known yet (unrecognized device)") {
        `when`("the journey starts") {
            val transition = strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = null))
            then("hands off to REGISTER's own journey to identify") {
                transition shouldBe
                    Transition.RequireSubJourney(AuthIntent.REGISTER, seedWith = RegisterState.Start, resumeWith = FastAccessState.Start)
            }
        }
    }

    given("Start, account known via a linked device credential") {
        val acc = account(method("device", AcrLevel.LOA2, details = deviceDetails()))
        `when`("the journey starts") {
            val transition = strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            then("suggests exactly that device credential, not a generic choice") {
                transition shouldBe
                    Transition.To(FastAccessState.PreferredAuth(ToolId("auth-device")))
            }
        }
    }

    given("Start, account known but no preferred device - other methods available") {
        val acc = account(method("sms", AcrLevel.LOA2))
        `when`("the journey starts") {
            val transition = strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            then("offers them via AuthChoice") {
                transition shouldBe
                    Transition.To(AuthChoice(Offer(listOf(ToolId("auth-sms")))))
            }
        }
    }

    given("Start, account known but has no active methods at all") {
        val acc = account()
        `when`("the journey starts") {
            val transition = strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            then("hands off to REGISTER's own journey rather than a dead end") {
                transition shouldBe
                    Transition.RequireSubJourney(AuthIntent.REGISTER, seedWith = RegisterState.Start, resumeWith = FastAccessState.Start)
            }
        }
    }

    given("Start, account whose evidence already meets the floor") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        `when`("resumed after a sub-journey finished (RE_IDENTIFY or REGISTER alike)") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA2)
            val transition = strategy.transition(FastAccessState.Start, event, theCtx)
            then("re-checks satisfaction via afterProof instead of re-running firstOffer") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("Start, waiting for a sub-journey") {
        `when`("the sub-journey is declined instead (SubJourneyCancelled)") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
            val transition = strategy.transition(FastAccessState.Start, event, ctx())
            then("gives up on its own rather than re-requesting the identical sub-journey again") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("PreferredAuth, account with the preferred device and other methods") {
        val acc = account(method("device", AcrLevel.LOA2, details = deviceDetails()), method("sms", AcrLevel.LOA2))
        `when`("the preferred tool is declined") {
            val transition = strategy.transition(FastAccessState.PreferredAuth(ToolId("auth-device")), JourneyEvent.Abandoned(AuthDeviceDescriptor), ctx(account = acc))
            then("falls back to the account's other methods") {
                transition shouldBe
                    Transition.To(AuthChoice(Offer(listOf(ToolId("auth-sms")), emptySet())))
            }
        }
    }

    given("PreferredAuth, evidence that already satisfies the floor") {
        val acc = account(method("device", AcrLevel.LOA2, details = deviceDetails()))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc), acrFloor = AcrLevel.LOA2)
        val state = FastAccessState.PreferredAuth(ToolId("auth-device"))

        `when`("a proof just completed") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("device"))
            val event = JourneyEvent.Completed(AuthDeviceDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("accepts the proof") {
                transition shouldBe
                    Transition.Perform(Action.AcceptProof(AuthDeviceDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after the accepted proof (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("AuthChoice, more than one offered candidate") {
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"), ToolId("auth-password"))))
        val acc = account(method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2))
        `when`("one candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsDescriptor), ctx(account = acc))
            then("keeps the run in AuthChoice with the rest still offered") {
                transition shouldBe
                    Transition.To(state.declining(ToolId("auth-sms")))
            }
        }
    }

    given("AuthChoice, a single offered candidate") {
        val acc = account(method("sms", AcrLevel.LOA2))
        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(AuthChoice(Offer(listOf(ToolId("auth-sms")))), JourneyEvent.Abandoned(AuthSmsDescriptor), ctx(account = acc))
            then("hands off to REGISTER's own journey, same as an account with nothing usable at all") {
                transition shouldBe
                    Transition.RequireSubJourney(AuthIntent.REGISTER, seedWith = RegisterState.Start, resumeWith = FastAccessState.Start)
            }
        }
    }

    given("Enrolling, reached inline after a proof left the floor unsatisfied with nothing else to try") {
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false)

        `when`("abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsDescriptor), ctx())
            then("re-offers the same full choice, the tool just backed out of included") {
                transition shouldBe Transition.To(state.withActive(null))
            }
        }
    }

    given("Enrolling, reached inline after a proof left the floor unsatisfied with nothing else to try, the floor is reached once the new method counts") {
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false)
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)

        `when`("a method was just enrolled") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("adopts the credential") {
                transition shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after the adopted credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes directly - FAST_ACCESS never carries an email obligation") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("afterProof's own dead end: no enrollment tool left at all (all backend-disabled), re-identification could still close the gap") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val onlyAuthTools = setOf(ToolId("auth-sms"))
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2, availableTools = onlyAuthTools + setOf(ToolId("ident-fsc"), ToolId("ident-eid")))

        `when`("a proof just completed") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            val transition = strategy.transition(state, event, theCtx)
            then("accepts the proof") {
                transition shouldBe
                    Transition.Perform(Action.AcceptProof(AuthSmsDescriptor, outcome), resumeState = state)
            }
        }

        `when`("resumed after the accepted proof (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("requires the shared RE_IDENTIFY sub-journey instead of aborting") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.RE_IDENTIFY,
                        seedWith = ReIdentifyState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                        resumeWith = FastAccessState.Start
                    )
            }
        }
    }

    given("afterProof's own dead end: no enrollment tool left at all (all backend-disabled), nothing can help at all") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val onlyAuthTools = setOf(ToolId("auth-sms"))
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2, availableTools = onlyAuthTools)

        `when`("resumed after the accepted proof (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("aborts with a reason") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }
})
