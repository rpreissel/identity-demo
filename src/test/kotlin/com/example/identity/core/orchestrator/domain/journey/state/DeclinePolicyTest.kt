package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.journey.afterAbandoning
import com.example.identity.core.orchestrator.domain.journey.narrowedBy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * What backing out of a tool means, by the offer's [DeclinePolicy] (docs/04-orchestrierung.md,
 * "Zwei Sorten von Übergang"): the state answers it, and only an exhausted or optional offer
 * reaches a journey. The journey tests then only cover what follows.
 */
class DeclinePolicyTest : BehaviorSpec({

    val sms = ToolId("auth-sms")
    val password = ToolId("auth-password")
    val all = setOf(sms, password)

    given("a mandatory offer (Enrolling)") {
        val state = Enrolling(Offer(listOf(sms, password)))

        `when`("a tool is backed out of") {
            val next = state.afterAbandoning(sms, all)

            then("the full offer comes back, nothing declined, the journey not asked") {
                next shouldBe state.withActive(null)
                (next as OfferingState).declined shouldBe emptySet()
            }
        }
    }

    given("a fallback offer (AuthChoice) with two tools") {
        val state = AuthChoice(Offer(listOf(sms, password)))

        `when`("one is backed out of") {
            val next = state.afterAbandoning(sms, all)

            then("the offer narrows to the rest, the journey not asked") {
                next shouldBe state.declining(sms)
            }
        }

        `when`("the other is backed out of as well") {
            val next = state.declining(sms).afterAbandoning(password, all)

            then("the state cannot decide any more: the journey hears OfferExhausted with both declined") {
                next.shouldBeNull()
                state.declining(sms).narrowedBy(password).declined shouldBe all
            }
        }

        `when`("one is backed out of while the other is not available on this channel") {
            val next = state.afterAbandoning(sms, setOf(sms))

            then("availability exhausts the offer just like a decline") {
                next.shouldBeNull()
            }
        }
    }

    given("an optional offer (Assigning)") {
        val state = RegisterState.Assigning(Offer(listOf(ToolId("ident-kvnr"), ToolId("ident-other"))))

        `when`("a tool is backed out of, another still offered") {
            val next = state.afterAbandoning(ToolId("ident-kvnr"), setOf(ToolId("ident-kvnr"), ToolId("ident-other")))

            then("the whole step is skipped at once: the journey decides") {
                next.shouldBeNull()
            }
        }
    }
})
