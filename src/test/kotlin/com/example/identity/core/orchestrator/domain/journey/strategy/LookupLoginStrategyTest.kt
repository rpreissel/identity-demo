package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.domain.journey.strategy.LookupLoginStrategy
import com.example.identity.tools.auth_sms.AuthSmsLookupDescriptor
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.texts.Text
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [LookupLoginStrategy] - logging into an existing account from an unpaired
 * device (docs/04-orchestrierung.md, "LOOKUP_LOGIN"). No Spring context, no HTTP.
 */
class LookupLoginStrategyTest : BehaviorSpec({

    val strategy = LookupLoginStrategy()

    given("the intent") {
        then("is LOOKUP_LOGIN") {
            strategy.intent shouldBe AuthIntent.LOOKUP_LOGIN
        }
    }

    given("initialState") {
        then("is Start") {
            strategy.initialState(ctx()) shouldBe LookupLoginState.Start
        }
    }

    given("Credential, the account-resolving lookup tool offered") {
        val state = LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup"))))

        `when`("the tool completes Authenticated with its own account (the first, account-resolving proof)") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"), subject = Subject.Account(AccountId(42L)))
            val event = JourneyEvent.Completed(AuthSmsLookupDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("trusts the tool's own account") {
                transition shouldBe
                    Transition.Perform(Action.AcceptProof(AuthSmsLookupDescriptor, outcome), resumeState = state)
            }
        }

        `when`("the tool completes Identified") {
            val result = runCatching {
                strategy.transition(state, JourneyEvent.Completed(AuthSmsLookupDescriptor, ToolOutcome.Completed.Identified(claims = listOf(com.example.identity.contract.tool_api.claims.Claim(com.example.identity.contract.tool_api.claims.AttributeType.PERSON_ID, "P000000001", com.example.identity.contract.tool_api.claims.ClaimSource.PERSON_DIRECTORY)))), ctx())
            }
            then("Identified is not offered by any state of this intent") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("the tool completes Enrolled") {
            val result = runCatching {
                strategy.transition(
                    state,
                    JourneyEvent.Completed(AuthSmsLookupDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))),
                    ctx()
                )
            }
            then("Enrolled is not offered by any state of this intent") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("Credential, auth-invite offered in the App") {
        val state = LookupLoginState.Credential(Offer(listOf(ToolId("auth-invite"))))

        `when`("a one-time password proves an invitation, not an account") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("invite"), subject = Subject.Invitation(InvitationId("a".repeat(64))))
            val transition = strategy.transition(state, JourneyEvent.Completed(AuthSmsLookupDescriptor, outcome), ctx())
            then("aborts before anything is bound: process access is for the website only (ADR-48)") {
                transition shouldBe Transition.Abort(Text("Ein Einmalkennwort gilt nur auf der Website"))
            }
        }
    }

    given("AdditionalFactor") {
        val state = LookupLoginState.AdditionalFactor(Offer(listOf(ToolId("auth-sms"))))

        `when`("a tool completes Authenticated (any further proof)") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
            val event = JourneyEvent.Completed(AuthSmsLookupDescriptor, outcome)
            val transition = strategy.transition(state, event, ctx())
            then("never trusts a submitted account") {
                transition shouldBe
                    Transition.Perform(Action.AcceptProof(AuthSmsLookupDescriptor, outcome), resumeState = state)
            }
        }
    }

    given("Start, with lookup-capable tools available") {
        `when`("started") {
            val transition = strategy.transition(LookupLoginState.Start, JourneyEvent.Started, ctx())
            then("offers every ACCOUNT_LOOKUP_AUTH tool in the catalog") {
                transition.shouldBeInstanceOf<Transition.To>()
                val to = transition.state
                to.shouldBeInstanceOf<LookupLoginState.Credential>()
                to.offered shouldContainExactly listOf(ToolId("auth-sms-lookup"), ToolId("auth-email-lookup"), ToolId("auth-password-lookup"), ToolId("auth-qr-lookup"), ToolId("auth-invite"))
            }
        }

        `when`("a RE_IDENTIFY sub-journey was declined instead (SubJourneyCancelled)") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
            val transition = strategy.transition(LookupLoginState.Start, event, ctx())
            then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    given("Start, but none of the lookup-capable tools are available client-side") {
        `when`("started") {
            val transition = strategy.transition(LookupLoginState.Start, JourneyEvent.Started, ctx(availableTools = emptySet()))
            then("aborts - no login path without a paired device exists") {
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    given("Start, an account with LOA1 evidence and a device linked to nobody") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), linkedAccountId = null)

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA2)
            val transition = strategy.transition(LookupLoginState.Start, event, theCtx)
            then("delegates to the same settle-or-raise check as any other proof") {
                transition shouldBe Transition.To(LookupLoginState.OfferBinding)
            }
        }
    }

    given("Credential, more than one offered candidate") {
        val state = LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup"), ToolId("auth-email-lookup"))))

        `when`("one is abandoned, another remains") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsLookupDescriptor), ctx())
            then("advances, marking only that one declined") {
                transition shouldBe
                    Transition.To(state.declining(ToolId("auth-sms-lookup")))
            }
        }
    }

    given("Credential, a single offered candidate") {
        val state = LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup"))))

        `when`("the last offered candidate is abandoned") {
            val transition = strategy.transition(state, JourneyEvent.Abandoned(AuthSmsLookupDescriptor), ctx())
            then("cancels - giving up on the very first proof is not an error") {
                transition shouldBe Transition.Cancel
            }
        }
    }

    // Credential/AdditionalFactor after a proof: settleOrRaise runs on ActionCompleted.
    given("Credential after a proof, the floor already satisfied, the device linked to nobody") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1, linkedAccountId = null)

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx)
            then("offers the optional device-binding prompt, the device being linked to nobody") {
                transition shouldBe
                    Transition.To(LookupLoginState.OfferBinding)
            }
        }
    }

    given("Credential after a proof, the floor satisfied and this device already linked to this very account") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1, linkedAccountId = acc.accountId)

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx)
            then("signs in straight away - there is no link to offer") {
                transition shouldBe
                    Transition.Authenticated
            }
        }
    }

    given("Credential after a proof, the floor not yet satisfied, but another active method can help") {
        val acc = account(method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx)
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
            val transition = strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx)
            then("requires the shared RE_IDENTIFY sub-journey - it only re-confirms this account, never adopts a different one") {
                transition shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.RE_IDENTIFY,
                        seedWith = ReIdentifyState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
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
            availableTools = StrategyTestFixtures.allToolIds - setOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect"))
        )

        `when`("the proof's action completes (ActionCompleted)") {
            val transition = strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx)
            then("aborts with a reason - never a silent enrollment fallback (this intent has none)") {
                transition.shouldBeInstanceOf<Transition.Abort>()
                transition.reason.template shouldContain "nicht erreichbar"
            }
        }
    }

    given("OfferBinding") {
        val state = LookupLoginState.OfferBinding

        `when`("accepted") {
            val transition = strategy.transition(state, JourneyEvent.Answered("accept"), ctx())
            then("links the device") {
                transition shouldBe
                    Transition.Perform(Action.LinkDevice, resumeState = state)
            }
        }

        `when`("resumed after linking (ActionCompleted)") {
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, ctx())
            then("finishes") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("declined") {
            val transition = strategy.transition(state, JourneyEvent.Answered("decline"), ctx())
            then("finishes without linking") {
                transition shouldBe Transition.Authenticated
            }
        }

        `when`("answered with something unrecognized") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Answered("maybe"), ctx()) }
            then("fails loudly") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("any non-Answered/ActionCompleted event arrives (Started)") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Started, ctx()) }
            then("rejects it - this state never runs a tool") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
