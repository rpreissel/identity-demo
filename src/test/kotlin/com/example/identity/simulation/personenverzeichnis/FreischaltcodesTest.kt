package com.example.identity.simulation.personenverzeichnis

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.simulation.personenverzeichnis.internal.Brief
import com.example.identity.simulation.personenverzeichnis.internal.BriefRepository
import com.example.identity.simulation.personenverzeichnis.internal.Freischaltcode
import com.example.identity.simulation.personenverzeichnis.internal.FreischaltcodeRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Optional

private val PERSON = PartnerNumber("P000000007")

private fun code(id: Long, expiresIn: Long, revoked: Boolean = false) =
    Freischaltcode(
        personId = PERSON,
        codeHash = "h",
        expiresAt = TEST_NOW.plusSeconds(expiresIn),
        revokedAt = if (revoked) TEST_NOW else null
    ).also { it.id = id }

/** The register's codes for [PERSON] under hash "h" are [stored]. */
private fun registerHolding(vararg stored: Freischaltcode): Freischaltcodes {
    val codes = mockk<FreischaltcodeRepository>()
    every { codes.findByPersonIdAndCodeHash(PERSON, "h") } returns stored.toList()
    return Freischaltcodes(codes, mockk(), clock = TEST_CLOCK)
}

class FreischaltcodesTest : BehaviorSpec({

    given("a current code") {
        val service = registerHolding(code(1, 600))

        `when`("it is checked") {
            val valid = service.pruefe(PERSON, "h")

            then("it is valid") {
                valid shouldBe true
            }
        }
    }

    given("an expired code") {
        val service = registerHolding(code(1, -1))

        `when`("it is checked") {
            val valid = service.pruefe(PERSON, "h")

            then("it is not valid") {
                valid shouldBe false
            }
        }
    }

    given("a revoked code") {
        val service = registerHolding(code(1, 600, revoked = true))

        `when`("it is checked") {
            val valid = service.pruefe(PERSON, "h")

            then("it is not valid") {
                valid shouldBe false
            }
        }
    }

    given("no code under the hash") {
        val service = registerHolding()

        `when`("the hash is checked") {
            val valid = service.pruefe(PERSON, "h")

            then("it is not valid") {
                valid shouldBe false
            }
        }
    }

    given("a register that stores codes and letters") {
        val codes = mockk<FreischaltcodeRepository>()
        val briefe = mockk<BriefRepository>()
        val service = Freischaltcodes(codes, briefe, clock = TEST_CLOCK)
        val storedCode = slot<Freischaltcode>()
        val storedBrief = slot<Brief>()
        every { codes.save(capture(storedCode)) } answers { storedCode.captured.also { it.id = 11L } }
        every { briefe.save(capture(storedBrief)) } answers { storedBrief.captured.also { it.id = 21L } }

        `when`("a code is issued") {
            val brief = service.ausstellen(PERSON, TEST_NOW.plusSeconds(3600))

            then("the letter carries the plaintext whose digest is what the register stores") {
                brief.freischaltcodeId shouldBe 11L
                storedCode.captured.codeHash shouldBe Freischaltcodes.hash(brief.code)
                storedCode.captured.codeHash shouldNotBe brief.code
            }
        }
    }

    given("a revoked newer code and a valid older one, each with its letter") {
        val codes = mockk<FreischaltcodeRepository>()
        val briefe = mockk<BriefRepository>()
        val service = Freischaltcodes(codes, briefe, clock = TEST_CLOCK)
        every { codes.findByPersonIdOrderByIdDesc(PERSON) } returns listOf(code(2, 600, revoked = true), code(1, 600))
        every { briefe.findByPersonIdOrderByIdDesc(PERSON) } returns listOf(
            Brief(personId = PERSON, freischaltcodeId = 2L, code = "NEU", versandtAm = TEST_NOW),
            Brief(personId = PERSON, freischaltcodeId = 1L, code = "ALT", versandtAm = TEST_NOW)
        )

        `when`("the newest valid code is looked up") {
            val newest = service.juengsterGueltigerCode(PERSON)

            then("it skips the letter of the revoked code") {
                newest shouldBe "ALT"
            }
        }
    }

    given("a stored code with id 1, and no code with id 2") {
        val codes = mockk<FreischaltcodeRepository>()
        val service = Freischaltcodes(codes, mockk(), clock = TEST_CLOCK)
        val existing = code(1, 600)
        every { codes.findById(1L) } returns Optional.of(existing)
        every { codes.findById(2L) } returns Optional.empty()

        `when`("code 1 is revoked") {
            val revoked = service.widerrufen(1L)

            then("it reports success and marks the code") {
                revoked shouldBe true
                existing.revokedAt shouldNotBe null
            }
        }

        `when`("code 2 is revoked") {
            val revoked = service.widerrufen(2L)

            then("it reports the id as unknown") {
                revoked shouldBe false
            }
        }
    }

    given("the code VALIDCODE") {
        `when`("it is digested") {
            val digest = Freischaltcodes.hash("VALIDCODE")

            then("it matches the SHA-256 the seed SQL computes") {
                digest shouldBe "666b50c34a52330b8b7b8d1136514ca145af1061c99085edc03d643e1da1a714"
            }
        }
    }
})
