package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.AcrLevels
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Unit test of [AcrLevels], the orchestrator's part of the ACR taxonomy ([AcrLevel] is tested in
 * tool_api): the session policy.
 */
class AcrLevelsTest : BehaviorSpec({

    given("bump, the MFA rule's one-step-up helper") {
        listOf(
            Triple(AcrLevel.LOA1, 1, AcrLevel.LOA2),
            Triple(AcrLevel.LOA1, 2, AcrLevel.LOA3),
            // Capped at the highest known level, never past it.
            Triple(AcrLevel.LOA3, 1, AcrLevel.LOA3),
            Triple(AcrLevel.LOA2, 10, AcrLevel.LOA3),
        ).forEach { (start, steps, expected) ->
            `when`("bumping ${start.value} by $steps") {
                val bumped = AcrLevels.bump(start, steps = steps)

                then("it is ${expected.value}") {
                    bumped shouldBe expected
                }
            }
        }
    }
})
