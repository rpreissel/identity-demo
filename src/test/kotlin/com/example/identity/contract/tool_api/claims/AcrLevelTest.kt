package com.example.identity.contract.tool_api.claims

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit test of [AcrLevel], the ACR taxonomy both sides of the tool contract share. Every security
 * decision builds on its ordering, so an error here would silently corrupt all of them.
 */
class AcrLevelTest : BehaviorSpec({

    given("parse, the defensive edge reader") {
        then("maps known strings to their level") {
            AcrLevel.parse("loa2") shouldBe AcrLevel.LOA2
            AcrLevel.parse("none") shouldBe AcrLevel.NONE
        }

        then("answers null for unknown and null - an edge reader must never invent a level nor crash on one") {
            AcrLevel.parse("loa4") shouldBe null
            AcrLevel.parse("bogus") shouldBe null
            AcrLevel.parse(null) shouldBe null
        }
    }

    given("the constructor's in-process guard") {
        then("rejects an unknown level loudly - a typo in code must not survive") {
            shouldThrow<IllegalArgumentException> { AcrLevel("loa4") }.message shouldContain "loa4"
        }
    }

    given("rank") {
        then("known levels rank in the documented order") {
            AcrLevel.rank(AcrLevel.NONE) shouldBe 0
            AcrLevel.rank(AcrLevel.LOA1) shouldBe 1
            AcrLevel.rank(AcrLevel.LOA2) shouldBe 2
            AcrLevel.rank(AcrLevel.LOA3) shouldBe 3
        }

        then("null ranks as 'none'") {
            AcrLevel.rank(null) shouldBe 0
        }
    }

    given("levelAt, the inverse of rank") {
        then("round-trips every known level") {
            AcrLevel.KNOWN.forEach {
                AcrLevel.levelAt(AcrLevel.rank(AcrLevel(it))).value shouldBe it
            }
        }

        then("an out-of-range rank falls back to NONE rather than throwing") {
            AcrLevel.levelAt(99) shouldBe AcrLevel.NONE
            AcrLevel.levelAt(-1) shouldBe AcrLevel.NONE
        }
    }

    given("max/min") {
        then("max picks the higher-ranked level") {
            AcrLevel.max(AcrLevel.LOA1, AcrLevel.LOA2) shouldBe AcrLevel.LOA2
            AcrLevel.max(AcrLevel.LOA2, AcrLevel.LOA1) shouldBe AcrLevel.LOA2
        }

        then("max treats a null side as absent, not as the winner") {
            AcrLevel.max(null, AcrLevel.LOA2) shouldBe AcrLevel.LOA2
            AcrLevel.max(AcrLevel.LOA2, null) shouldBe AcrLevel.LOA2
            AcrLevel.max(null, null) shouldBe AcrLevel.NONE
        }

        then("min picks the lower-ranked level") {
            AcrLevel.min(AcrLevel.LOA1, AcrLevel.LOA2) shouldBe AcrLevel.LOA1
        }

        then("min treats either side being null as NONE - a missing value never counts as satisfied") {
            AcrLevel.min(null, AcrLevel.LOA2) shouldBe AcrLevel.NONE
            AcrLevel.min(AcrLevel.LOA2, null) shouldBe AcrLevel.NONE
        }
    }

    given("compareTo") {
        then("orders levels by their taxonomy rank") {
            (AcrLevel.LOA1 < AcrLevel.LOA2) shouldBe true
            (AcrLevel.LOA3 > AcrLevel.LOA2) shouldBe true
        }
    }
})
