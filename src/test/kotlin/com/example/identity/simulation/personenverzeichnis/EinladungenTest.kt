package com.example.identity.simulation.personenverzeichnis

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.simulation.personenverzeichnis.internal.Brief
import com.example.identity.simulation.personenverzeichnis.internal.BriefRepository
import com.example.identity.simulation.personenverzeichnis.internal.Einladung
import com.example.identity.simulation.personenverzeichnis.internal.EinladungRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val PERSON = PartnerNumber("P000000007")
private const val VORGANG = "bonusprogramm"
private val GUELTIG_BIS: Instant = TEST_NOW.plus(Duration.ofDays(14))

/** The register over in-memory invitations and letters; [clock] is the register's time. */
private class Register {
    val gespeichert = mutableListOf<Einladung>()
    val einladungen = mockk<EinladungRepository> {
        every { save(any<Einladung>()) } answers { firstArg<Einladung>().also { e -> gespeichert.removeIf { it.id == e.id }; gespeichert.add(e) } }
        every { findByPersonIdOrderByAusgestelltAmDesc(PERSON) } answers { gespeichert.toList() }
    }
    val briefe = mockk<BriefRepository> {
        every { save(any<Brief>()) } answers { firstArg<Brief>().apply { id = 1L } }
    }
    val personen = mockk<Personenverzeichnis> { every { findPersonById(PERSON) } returns mockk() }

    fun at(clock: Clock) = Einladungen(einladungen, briefe, personen, mockk(relaxed = true), clock)
}

/**
 * The one-time password of an invitation and how long it opens it
 * (docs/06-ablaeufe.md Abschnitt 9, docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
 */
class EinladungenTest : BehaviorSpec({

    given("the register") {
        val register = Register()

        `when`("it issues many invitations") {
            val kennwoerter = List(500) { checkNotNull(register.at(TEST_CLOCK).ausstellen(PERSON, VORGANG, "loa1", GUELTIG_BIS)).code }

            then("each letter shows twelve characters in groups of four") {
                kennwoerter.forEach { it shouldMatch Regex("[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}") }
            }

            then("the characters come from an alphabet of 31, without I, L, O, 0 and 1") {
                val zeichen = kennwoerter.flatMap { it.replace("-", "").toList() }.toSet()
                zeichen shouldBe Einladungen.ALPHABET.toSet()
                Einladungen.ALPHABET.length shouldBe 31
                Einladungen.ALPHABET.toSet().size shouldBe 31
                zeichen.intersect(setOf('I', 'L', 'O', '0', '1')) shouldBe emptySet()
            }
        }
    }

    given("an invitation issued with a deadline in two weeks") {
        val register = Register()
        val kennwort = checkNotNull(register.at(TEST_CLOCK).ausstellen(PERSON, VORGANG, "loa2", GUELTIG_BIS)).code

        `when`("the person logs in with it twice before the deadline") {
            val einladungen = register.at(TEST_CLOCK)
            val erste = einladungen.redeem(PERSON, kennwort)
            val zweite = einladungen.redeem(PERSON, kennwort)

            then("both logins open the same invitation - it is not used up") {
                erste.shouldNotBeNull()
                zweite shouldBe erste
                erste.process shouldBe VORGANG
            }
        }

        `when`("the person logs in with it after the deadline, with nobody having ended it") {
            val danach = Clock.fixed(GUELTIG_BIS.plusSeconds(1), ZoneOffset.UTC)
            val grant = register.at(danach).redeem(PERSON, kennwort)
            val ansicht = register.at(danach).fuerPerson(PERSON)

            then("the password opens nothing any more") {
                grant.shouldBeNull()
            }

            then("the invitation shows as no longer open, though it was neither completed nor revoked") {
                val einladung = ansicht.single()
                einladung.offen shouldBe false
                einladung.abgeschlossenAm.shouldBeNull()
                einladung.widerrufenAm.shouldBeNull()
            }
        }
    }
})
