package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.contract.tool_api.ToolId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import com.example.identity.core.orchestrator.domain.AuthIntent

class RequireSubJourneyInvariantTest : BehaviorSpec({
    given("a resume state that carries an offer") {
        val resumeWith = AuthChoice(Offer(listOf(ToolId("auth-sms"))))

        `when`("a sub-journey request resuming there is built") {
            val result = runCatching {
                Transition.RequireSubJourney(intent = AuthIntent.RE_IDENTIFY, seedWith = FastAccessState.Start, resumeWith = resumeWith)
            }

            then("it is rejected at construction - the offer must be recomputed on return") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldContain "recomputed"
            }
        }
    }
})
