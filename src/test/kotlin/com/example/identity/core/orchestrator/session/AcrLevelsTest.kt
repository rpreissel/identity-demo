package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.AcrLevels
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Unit test of [AcrLevels], the orchestrator's part of the ACR taxonomy ([AcrLevel] is tested in
 * tool_api): the session policy plus the String mirrors used at the JPA and wire boundaries. An
 * edge reader must never invent a level, so an unknown or missing string counts as "none".
 */
class AcrLevelsTest : BehaviorSpec({

    given("rank, the String mirror of the typed rank") {
        mapOf("none" to 0, "loa1" to 1, "loa2" to 2, "loa3" to 3, "bogus" to 0, null to 0).forEach { (level, expected) ->
            `when`("ranking ${level ?: "null"}") {
                val rank = AcrLevels.rank(level)

                then("it is $expected") {
                    rank shouldBe expected
                }
            }
        }
    }

    given("levelAt, the inverse of rank") {
        listOf("none", "loa1", "loa2", "loa3").forEach { level ->
            `when`("looking up the rank of $level") {
                val roundTrip = AcrLevels.levelAt(AcrLevels.rank(level))

                then("it gives back $level") {
                    roundTrip shouldBe level
                }
            }
        }

        listOf(99, -1).forEach { rank ->
            `when`("looking up the out-of-range rank $rank") {
                val level = AcrLevels.levelAt(rank)

                then("it falls back to none") {
                    level shouldBe "none"
                }
            }
        }
    }

    given("max, the higher-ranked of two strings") {
        listOf(
            Triple("loa1", "loa2", "loa2"),
            Triple("loa2", "loa1", "loa2"),
            // A null side is absent, not the winner.
            Triple(null, "loa2", "loa2"),
            Triple("loa2", null, "loa2"),
            Triple(null, null, "none"),
            Triple("bogus", "loa1", "loa1"),
        ).forEach { (a, b, expected) ->
            `when`("taking the max of $a and $b") {
                val max = AcrLevels.max(a, b)

                then("it is $expected") {
                    max shouldBe expected
                }
            }
        }
    }

    given("min, the lower-ranked of two strings") {
        listOf(
            Triple("loa1", "loa2", "loa1"),
            // A missing or unknown value never counts as satisfied.
            Triple(null, "loa2", "none"),
            Triple("loa2", null, "none"),
            Triple("bogus", "loa1", "none"),
        ).forEach { (a, b, expected) ->
            `when`("taking the min of $a and $b") {
                val min = AcrLevels.min(a, b)

                then("it is $expected") {
                    min shouldBe expected
                }
            }
        }
    }

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
