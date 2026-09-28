package com.example.identity.simulation.personenverzeichnis

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

class FreischaltcodesTest : BehaviorSpec({

    fun code(id: Long, expiresIn: Long, revoked: Boolean = false) =
        Freischaltcode(
            personId = "P000000007",
            codeHash = "h",
            expiresAt = TEST_NOW.plusSeconds(expiresIn),
            revokedAt = if (revoked) TEST_NOW else null
        ).also { it.id = id }

    given("pruefe") {
        val codes = mockk<FreischaltcodeRepository>()
        val service = Freischaltcodes(codes, mockk(), clock = TEST_CLOCK)

        then("a current code is valid") {
            every { codes.findByPersonIdAndCodeHash("P000000007", "h") } returns listOf(code(1, 600))
            service.pruefe("P000000007", "h") shouldBe true
        }
        then("an expired code is not") {
            every { codes.findByPersonIdAndCodeHash("P000000007", "h") } returns listOf(code(1, -1))
            service.pruefe("P000000007", "h") shouldBe false
        }
        then("a revoked code is not") {
            every { codes.findByPersonIdAndCodeHash("P000000007", "h") } returns listOf(code(1, 600, revoked = true))
            service.pruefe("P000000007", "h") shouldBe false
        }
        then("an unknown hash is not") {
            every { codes.findByPersonIdAndCodeHash("P000000007", "h") } returns emptyList()
            service.pruefe("P000000007", "h") shouldBe false
        }
    }

    given("ausstellen") {
        val codes = mockk<FreischaltcodeRepository>()
        val briefe = mockk<BriefRepository>()
        val service = Freischaltcodes(codes, briefe, clock = TEST_CLOCK)
        val storedCode = slot<Freischaltcode>()
        val storedBrief = slot<Brief>()
        every { codes.save(capture(storedCode)) } answers { storedCode.captured.also { it.id = 11L } }
        every { briefe.save(capture(storedBrief)) } answers { storedBrief.captured.also { it.id = 21L } }

        then("the letter carries the plaintext whose digest is what the register stores") {
            val brief = service.ausstellen("P000000007", TEST_NOW.plusSeconds(3600))

            brief.freischaltcodeId shouldBe 11L
            storedCode.captured.codeHash shouldBe Freischaltcodes.hash(brief.code)
            storedCode.captured.codeHash shouldNotBe brief.code
        }
    }

    given("juengsterGueltigerCode") {
        val codes = mockk<FreischaltcodeRepository>()
        val briefe = mockk<BriefRepository>()
        val service = Freischaltcodes(codes, briefe, clock = TEST_CLOCK)
        every { codes.findByPersonIdOrderByIdDesc("P000000007") } returns listOf(code(2, 600, revoked = true), code(1, 600))
        every { briefe.findByPersonIdOrderByIdDesc("P000000007") } returns listOf(
            Brief(personId = "P000000007", freischaltcodeId = 2L, code = "NEU", versandtAm = TEST_NOW),
            Brief(personId = "P000000007", freischaltcodeId = 1L, code = "ALT", versandtAm = TEST_NOW)
        )

        then("it skips the letter of a revoked code") {
            service.juengsterGueltigerCode("P000000007") shouldBe "ALT"
        }
    }

    given("widerrufen") {
        val codes = mockk<FreischaltcodeRepository>()
        val service = Freischaltcodes(codes, mockk(), clock = TEST_CLOCK)
        val existing = code(1, 600)
        every { codes.findById(1L) } returns Optional.of(existing)
        every { codes.findById(2L) } returns Optional.empty()

        then("it marks the code and reports unknown ids") {
            service.widerrufen(1L) shouldBe true
            existing.revokedAt shouldNotBe null
            service.widerrufen(2L) shouldBe false
        }
    }

    given("digest") {
        then("it matches the SHA-256 the seed SQL computes for VALIDCODE") {
            Freischaltcodes.hash("VALIDCODE") shouldBe "666b50c34a52330b8b7b8d1136514ca145af1061c99085edc03d643e1da1a714"
        }
    }
})
