package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.state.DeviceLinkQuestion
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.tool
import com.example.identity.core.orchestrator.domain.journeys.LookupLoginJourney
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journeys.JourneyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [LookupLoginJourney] - logging into an existing account from an unpaired
 * device (docs/04-orchestrierung.md, "LOOKUP_LOGIN"). No Spring context, no HTTP.
 */
class LookupLoginJourneyTest : BehaviorSpec({

    val journey = LookupLoginJourney

    // The first proof, offered by a lookup tool that resolves the account itself.
    val smsLookupCredential = LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup"))))

    given("a new journey") {
        `when`("its first state is chosen") {
            val initial = journey.firstState

            then("it is Start") {
                initial shouldBe LookupLoginState.Start
            }
        }
    }

    given("Credential, the account-resolving lookup tool offered") {
        val state = smsLookupCredential

    }



    given("Start, with lookup-capable tools available") {
        `when`("started") {
            val transition = journey.transition(LookupLoginState.Start, JourneyEvent.Started, ctx())
            then("offers every ACCOUNT_LOOKUP_AUTH tool in the catalog") {
                transition.shouldBeInstanceOf<Transition.To>()
                val to = transition.state
                to.shouldBeInstanceOf<LookupLoginState.Credential>()
                to.offered shouldContainExactly listOf(ToolId("auth-sms-lookup"), ToolId("auth-email-lookup"), ToolId("auth-password-lookup"), ToolId("auth-qr-lookup"), ToolId("auth-invite-lookup"))
            }
        }
    }

    given("Start, but none of the lookup-capable tools are available client-side") {
        `when`("started") {
            val transition = journey.transition(LookupLoginState.Start, JourneyEvent.Started, ctx(availableTools = emptySet()))
            then("aborts - no login path without a paired device exists") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    given("Start, an account with LOA1 evidence and a device linked to nobody") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), linkedAccountId = null)

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(ReIdentifyJourney, achievedAcr = AcrLevel.LOA2)
            val transition = journey.transition(LookupLoginState.Start, event, theCtx)
            then("delegates to the same settle-or-raise check as any other proof") {
                transition shouldBe Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.OFFER))
            }
        }
    }

    given("Credential, a single offered candidate") {
        val state = smsLookupCredential

        `when`("the last offered candidate is abandoned") {
            val exhausted = state.declining(ToolId("auth-sms-lookup"))
            val transition = journey.transition(exhausted as LookupLoginState, JourneyEvent.OfferExhausted(exhausted.declined), ctx())
            then("cancels - giving up on the very first proof is not an error") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    // Credential/AdditionalFactor after a proof: settle runs on ActionCompleted.
    given("Credential after a proof, the floor already satisfied, the device linked to nobody") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1, linkedAccountId = null)

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = journey.transition(smsLookupCredential, JourneyEvent.ActionCompleted, theCtx)
            then("offers the optional device-binding prompt, the device being linked to nobody") {
                transition shouldBe
                    Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.OFFER))
            }
        }
    }

    given("Credential after a proof, the floor satisfied and this device already linked to this very account") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1, linkedAccountId = acc.accountId)

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = journey.transition(smsLookupCredential, JourneyEvent.ActionCompleted, theCtx)
            then("signs in straight away - there is no link to offer") {
                transition shouldBe
                    Transition.Authenticated
            }
        }
    }

    given("Credential after a proof, the floor satisfied and this device linked to a different account") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1, linkedAccountId = AccountId(999L))

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = journey.transition(smsLookupCredential, JourneyEvent.ActionCompleted, theCtx)

            then("warns before rebinding the device - never rebinds silently") {
                transition shouldBe Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND))
            }
        }
    }

    given("Credential after a proof, the floor not yet satisfied, but another active method can help") {
        val acc = account(method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = journey.transition(smsLookupCredential, JourneyEvent.ActionCompleted, theCtx)
            then("offers it via AdditionalFactor") {
                transition shouldBe
                    Transition.To(LookupLoginState.AdditionalFactor(Offer(listOf(ToolId("auth-password")))))
            }
        }
    }

    given("Credential after a proof, nothing active can help, but re-identification could") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = journey.transition(smsLookupCredential, JourneyEvent.ActionCompleted, theCtx)
            then("requires the shared RE_IDENTIFY sub-journey - it only re-confirms this account, never adopts a different one") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        ReIdentifyJourney.toReach(AcrLevel.LOA2),
                        resumeWith = LookupLoginState.Start
                    )
            }
        }
    }

    given("Credential after a proof, nothing can help at all, not even re-identification (backend-disabled)") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA2,
            availableTools = JourneyTestFixtures.allToolIds - setOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect"))
        )

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = journey.transition(smsLookupCredential, JourneyEvent.ActionCompleted, theCtx)
            then("aborts with a reason - never a silent enrollment fallback (this journey has none)") {
                transition.shouldBeInstanceOf<Transition.Abort>()
                transition.reason.template shouldContain "nicht erreichbar"
            }
        }
    }

    given("DeviceLinkQuestion, the device linked to nobody") {
        val state = DeviceLinkQuestion(DeviceLinkQuestion.Wording.OFFER)

        `when`("accepted") {
            val transition = journey.transition(state, JourneyEvent.Answered(Answer.ACCEPT), ctx())
            then("links the device") {
                transition shouldBe
                    Transition.Perform(Action.LinkDevice, resumeState = state)
            }
        }

        `when`("resumed after linking (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, ctx())
            then("finishes") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("declined") {
            val transition = journey.transition(state, JourneyEvent.Answered(Answer.DECLINE), ctx())
            then("finishes without linking") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("any non-Answered/ActionCompleted event arrives (Started)") {
            val result = runCatching { journey.transition(state, JourneyEvent.Started, ctx()) }
            then("rejects it - this state never runs a tool") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("DeviceLinkQuestion, the device linked to a different account") {
        val state = DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND)

        `when`("accepted") {
            val transition = journey.transition(state, JourneyEvent.Answered(Answer.ACCEPT), ctx())

            then("moves the device link to this account") {
                transition shouldBe Transition.Perform(Action.LinkDevice, resumeState = state)
            }
        }

        `when`("resumed after linking (ActionCompleted)") {
            val transition = journey.transition(state, JourneyEvent.ActionCompleted, ctx())

            then("signs in") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("declined") {
            val transition = journey.transition(state, JourneyEvent.Answered(Answer.DECLINE), ctx())

            then("signs in all the same; the device stays with the other account") {
                transition shouldBe Transition.Authenticated
            }
        }
    }
})
