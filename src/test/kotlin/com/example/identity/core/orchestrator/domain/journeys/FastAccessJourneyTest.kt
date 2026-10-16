package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journeys.FastAccessJourney
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.journeys.RegisterJourney
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.BINDING_KEY
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit coverage of [FastAccessJourney]: the fallback chain into a login, plus [Enrolling] for the
 * case it reaches without identifying anyone (docs/04-orchestrierung.md, "FAST_ACCESS").
 * Identification is [RegisterJourney]'s journey, see [RegisterJourneyTest]. Which action a
 * completed tool yields is [BookingTest]'s subject; the step after it is
 * [JourneyEvent.ActionCompleted] against a context that reflects the action.
 */
class FastAccessJourneyTest : BehaviorSpec({

    val journey = FastAccessJourney

    given("Start, no account known yet (unrecognized device)") {
        `when`("the journey starts") {
            val transition = journey.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = null))
            then("hands off to REGISTER's own journey to identify") {
                transition shouldBe
                    Transition.RequireSubJourney(RegisterJourney.start(), resumeWith = FastAccessState.Start)
            }
        }
    }

    given("Start, account known via a linked device credential") {
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = BINDING_KEY))
        `when`("the journey starts") {
            val transition = journey.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            then("suggests exactly that device credential, not a generic choice") {
                transition shouldBe
                    Transition.To(FastAccessState.PreferredAuth(ToolId("auth-device")))
            }
        }
    }

    given("Start, account known but no preferred device - other methods available") {
        val acc = account(method("sms", AcrLevel.LOA2))
        `when`("the journey starts") {
            val transition = journey.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            then("offers them via AuthChoice") {
                transition shouldBe
                    Transition.To(AuthChoice(Offer(listOf(ToolId("auth-sms")))))
            }
        }
    }

    given("Start, account known but has no active methods at all") {
        val acc = account()
        `when`("the journey starts") {
            val transition = journey.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            then("hands off to REGISTER's own journey rather than a dead end") {
                transition shouldBe
                    Transition.RequireSubJourney(RegisterJourney.start(), resumeWith = FastAccessState.Start)
            }
        }
    }

    given("Start, account whose evidence already meets the floor") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        `when`("resumed after a sub-journey finished (RE_IDENTIFY or REGISTER alike)") {
            val event = JourneyEvent.SubJourneyFinished(ReIdentifyJourney, achievedAcr = AcrLevel.LOA2)
            val transition = journey.transition(FastAccessState.Start, event, theCtx)
            then("re-checks satisfaction via afterProof instead of re-running firstOffer") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("PreferredAuth, account with the preferred device and other methods") {
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = BINDING_KEY), method("sms", AcrLevel.LOA2))
        `when`("the preferred tool is declined") {
            val transition = journey.transition(FastAccessState.PreferredAuth(ToolId("auth-device")), JourneyEvent.Abandoned(tool("auth-device")), ctx(account = acc))
            then("falls back to the account's other methods") {
                transition shouldBe
                    Transition.To(AuthChoice(Offer(listOf(ToolId("auth-sms")), emptySet())))
            }
        }
    }

    given("PreferredAuth, evidence that already satisfies the floor") {
        val acc = account(method("device", AcrLevel.LOA2, boundKeyRef = BINDING_KEY))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc), acrFloor = AcrLevel.LOA2)
        val state = FastAccessState.PreferredAuth(ToolId("auth-device"))

        `when`("resumed after the accepted proof (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("finishes") {
                transition shouldBe Transition.Authenticated
            }
        }
    }

    given("AuthChoice, a single offered candidate") {
        val acc = account(method("sms", AcrLevel.LOA2))
        `when`("the last offered candidate is abandoned") {
            val exhausted = AuthChoice(Offer(listOf(ToolId("auth-sms")))).declining(ToolId("auth-sms"))
            val transition = journey.transition(exhausted as FastAccessState, JourneyEvent.OfferExhausted(exhausted.declined), ctx(account = acc))
            then("hands off to REGISTER's own journey, same as an account with nothing usable at all") {
                transition shouldBe
                    Transition.RequireSubJourney(RegisterJourney.start(), resumeWith = FastAccessState.Start)
            }
        }
    }

    given("Enrolling, the new method lets the account reach the floor") {
        val state = Enrolling(Offer(listOf(ToolId("enroll-sms"))), emailObligation = false)
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)

        `when`("resumed after the adopted credential (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
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
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("requires the shared RE_IDENTIFY sub-journey instead of aborting") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        ReIdentifyJourney.toReach(AcrLevel.LOA2),
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
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("aborts, naming the missing fresh identification") {
                transition.shouldBeInstanceOf<Transition.Abort>().reason.template shouldContain "frische Identifizierung"
            }
        }
    }

    // PathToRequiredLevel.NEW_METHOD_FLOOR: a credential keeps the level it was enrolled under (ADR-5), so a session
    // below loa2 may only enroll after a fresh identification (docs/journeys/fast-access.md).
    given("AuthChoice, the proof falls short of loa2 while enrollment tools are available") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val state = AuthChoice(Offer(listOf(ToolId("auth-sms"))))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)

        `when`("resumed after the accepted proof (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("requires RE_IDENTIFY before any enrollment - a loa1 session never enrolls a method") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        ReIdentifyJourney.toReach(AcrLevel.LOA2),
                        resumeWith = FastAccessState.Start
                    )
            }
        }
    }

    given("Enrolling, the session still below loa2 and enrollment tools available") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val state = Enrolling(Offer(listOf(ToolId("enroll-password"))), emailObligation = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)

        `when`("resumed after an adopted credential that leaves the floor unmet (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("requires RE_IDENTIFY instead of offering the next enrollment") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        ReIdentifyJourney.toReach(AcrLevel.LOA2),
                        resumeWith = FastAccessState.Start
                    )
            }
        }
    }

    given("AuthChoice, the session already carries loa2 but the floor is loa3") {
        val acc = account(method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2))
        val state = AuthChoice(Offer(listOf(ToolId("auth-password"))))
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA3
        )

        `when`("resumed after the accepted proof (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, theCtx)
            then("offers enrollment directly - at loa2 no fresh identification is needed") {
                transition.shouldBeInstanceOf<Transition.To>().state.shouldBeInstanceOf<Enrolling>()
            }
        }
    }
})
