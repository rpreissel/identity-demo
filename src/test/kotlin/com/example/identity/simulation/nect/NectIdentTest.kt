package com.example.identity.simulation.nect

import com.example.identity.TEST_CLOCK
import com.example.identity.simulation.nect.internal.NectCase
import com.example.identity.simulation.nect.internal.NectCaseRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

private val MAX = NectAttributes(name = "Muster", vorname = "Max", geburtsdatum = LocalDate.of(1985, 6, 15))
private val EVERYTHING = NectAttribute.entries.toSet()

/** Nect over a mocked repository that keeps its cases in memory. */
private class Fixture {
    private val store = mutableMapOf<UUID, NectCase>()
    private val repository = mockk<NectCaseRepository>().also {
        val saved = slot<NectCase>()
        every { it.save(capture(saved)) } answers { store[checkNotNull(saved.captured.id)] = saved.captured; saved.captured }
        every { it.findById(any()) } answers { Optional.ofNullable(store[firstArg<UUID>()]) }
    }
    val nect = NectIdent(repository, clock = TEST_CLOCK)
}

/**
 * The provider's own rules, with its repository mocked: a case is redeemed exactly once, the eID
 * needs its PIN, an expired passport fails the case, a finished case cannot be finished again, and
 * the relying party gets only what it asked for and the document can deliver.
 */
class NectIdentTest : BehaviorSpec({

    given("an open case asking for everything") {
        val f = Fixture()
        val case = f.nect.createCase("/app/", EVERYTHING)

        `when`("it is redeemed before the user finishes") {
            val result = f.nect.redeem(case.caseId)

            then("it is still open") {
                result shouldBe NectResult.Open
            }
        }

        `when`("the user finishes with the eID card and the right PIN") {
            val callback = f.nect.complete(case.caseId, NectProcedure.EID, MAX, pin = "123456")

            then("the user is sent back to the callback, naming the case") {
                callback shouldBe "/app/?nectCaseId=${case.caseId}"
            }
        }

        `when`("the finished case is redeemed") {
            val result = f.nect.redeem(case.caseId)

            then("it hands over the procedure and the attributes") {
                result shouldBe NectResult.Identified(NectProcedure.EID, MAX)
            }
        }

        `when`("it is redeemed a second time") {
            val result = f.nect.redeem(case.caseId)

            then("there is nothing left - a case is redeemed exactly once") {
                result.shouldBeNull()
            }
        }
    }

    given("another open case") {
        val f = Fixture()
        val case = f.nect.createCase("/app/", EVERYTHING)

        `when`("the user finishes with the eID card and a wrong PIN") {
            val result = runCatching { f.nect.complete(case.caseId, NectProcedure.EID, MAX, pin = "000000") }

            then("it is refused and the case stays open") {
                shouldThrow<NectRejectedException> { result.getOrThrow() }
                f.nect.caseView(case.caseId)?.status shouldBe "OPEN"
            }
        }

        `when`("the user offers a passport with an address, which a passport cannot carry") {
            val result = runCatching { f.nect.complete(case.caseId, NectProcedure.EPASS, MAX.copy(ort = "Köln"), pin = null) }

            then("it is refused and the case stays open") {
                shouldThrow<NectRejectedException> { result.getOrThrow() }
                f.nect.caseView(case.caseId)?.status shouldBe "OPEN"
            }
        }

        `when`("the user offers a wallet PID with a pseudonym, which a wallet cannot carry") {
            val result = runCatching { f.nect.complete(case.caseId, NectProcedure.EUDI, MAX.copy(restrictedId = "X"), pin = null) }

            then("it is refused and the case stays open") {
                shouldThrow<NectRejectedException> { result.getOrThrow() }
                f.nect.caseView(case.caseId)?.status shouldBe "OPEN"
            }
        }
    }

    given("an open case, and an expired passport") {
        val f = Fixture()
        val case = f.nect.createCase("/app/", EVERYTHING)
        f.nect.complete(case.caseId, NectProcedure.EPASS, MAX, pin = null, expiryDate = LocalDate.now(TEST_CLOCK).minusDays(1))

        `when`("the case is redeemed") {
            val result = f.nect.redeem(case.caseId)

            then("it failed on the expired passport") {
                result shouldBe NectResult.Failed(NectFailure.PASSPORT_EXPIRED)
            }
        }
    }

    given("an open case whose callback already carries a query") {
        val f = Fixture()
        val case = f.nect.createCase("/app/?x=1", EVERYTHING)

        `when`("the user cancels") {
            val callback = f.nect.cancel(case.caseId)

            then("the user is sent back with the case appended to the query") {
                callback shouldBe "/app/?x=1&nectCaseId=${case.caseId}"
            }
        }

        `when`("the user tries to complete the cancelled case afterwards") {
            val result = runCatching { f.nect.complete(case.caseId, NectProcedure.EUDI, MAX, pin = null) }

            then("it is refused, and redeeming reports the cancellation") {
                shouldThrow<NectRejectedException> { result.getOrThrow() }
                f.nect.redeem(case.caseId) shouldBe NectResult.Cancelled
            }
        }
    }

    given("a case asking only for the names") {
        val f = Fixture()
        val case = f.nect.createCase("/app/", setOf(NectAttribute.FAMILY_NAME, NectAttribute.GIVEN_NAMES))

        then("the case shows what the relying party asked for") {
            f.nect.caseView(case.caseId)?.requested shouldBe listOf("family_name", "given_names")
        }

        `when`("the eID card shows everything and the case is redeemed") {
            f.nect.complete(case.caseId, NectProcedure.EID, MAX.copy(strasse = "Heidestraße 17", plz = "51147", ort = "Köln", restrictedId = "NECT-EID-1"), pin = "123456")
            val result = f.nect.redeem(case.caseId)

            then("redeeming hands on nothing beyond the names") {
                result shouldBe NectResult.Identified(NectProcedure.EID, NectAttributes(name = "Muster", vorname = "Max"))
            }
        }
    }

    given("an unknown case") {
        val f = Fixture()

        `when`("it is redeemed") {
            val result = f.nect.redeem(UUID.randomUUID())

            then("the answer is null") {
                result.shouldBeNull()
            }
        }
    }
})
