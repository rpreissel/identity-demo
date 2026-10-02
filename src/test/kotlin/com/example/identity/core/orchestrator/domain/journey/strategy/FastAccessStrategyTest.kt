package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.strategy.FastAccessStrategy
import com.example.identity.core.orchestrator.domain.journey.strategy.RegisterStrategy
import com.example.identity.tools.auth_device.AuthDeviceDescriptor
import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.auth_sms.EnrollSmsDescriptor
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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit coverage of [FastAccessStrategy]: the fallback chain into a login, plus [Enrolling] for the
 * case it reaches without identifying anyone (docs/04-orchestrierung.md, "FAST_ACCESS").
 * Identification is [RegisterStrategy]'s journey, see [RegisterStrategyTest]. Which action a
 * completed tool yields is [AuthEnrollCoreTest]'s subject; the step after it is
 * [JourneyEvent.ActionCompleted] against a context that reflects the action.
 */
class FastAccessStrategyTest : BehaviorSpec({

    val strategy = FastAccessStrategy()

    val deviceProof = ToolOutcome.Completed.Authenticated(amr = listOf("device"))
    val smsProof = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
    val smsEnrollment = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))

    // Every offering state hands a completed tool to AuthEnrollCore.proofAction and resumes where it was.
    listOf(
        FastAccessCompletion(
            FastAccessState.PreferredAuth(ToolId("auth-device")),
            JourneyEvent.Completed(AuthDeviceDescriptor, deviceProof),
            Action.AcceptProof(AuthDeviceDescriptor, deviceProof)
        ),
        FastAccessCompletion(
            AuthChoice(Offer(listOf(ToolId("auth-sms")))),
            JourneyEvent.Completed(AuthSmsDescriptor, smsProof),
            Action.AcceptProof(AuthSmsDescriptor, smsProof)
        ),
        FastAccessCompletion(
            Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false),
            JourneyEvent.Completed(EnrollSmsDescriptor, smsEnrollment),
            Action.AdoptCredential(EnrollSmsDescriptor, smsEnrollment)
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

    given("Enrolling, the new method lets the account reach the floor") {
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false)
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)

        `when`("resumed after the adopted credential (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes directly - FAST_ACCESS never carries an email obligation") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("AuthChoice, the proof falls short of loa2 and no enrollment tool is available, but identification is") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val onlyAuthTools = setOf(ToolId("auth-sms"))
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2, availableTools = onlyAuthTools + setOf(ToolId("ident-fsc"), ToolId("ident-eid")))

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

    given("AuthChoice, the proof falls short of loa2 and neither enrollment nor identification is available") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val onlyAuthTools = setOf(ToolId("auth-sms"))
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2, availableTools = onlyAuthTools)

        `when`("resumed after the accepted proof (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("aborts, naming the missing fresh identification") {
                transition.shouldBeInstanceOf<Transition.Abort>().reason.template shouldContain "frische Identifizierung"
            }
        }
    }
})

/** One row of the completed-tool table: the state, the completion it receives, the action it performs. */
private data class FastAccessCompletion(val state: FastAccessState, val event: JourneyEvent.Completed, val action: Action)
