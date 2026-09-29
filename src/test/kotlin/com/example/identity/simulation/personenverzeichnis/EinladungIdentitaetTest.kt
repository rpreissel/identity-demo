package com.example.identity.simulation.personenverzeichnis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The invitation's id is what the business system computes on its own to end an invitation
 * (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md), so its formula is pinned to a fixed value.
 */
class EinladungIdentitaetTest : BehaviorSpec({

    given("Person, Einmalkennwort und Vorgang") {
        then("ist die Id SHA-256 über personId:KENNWORT:vorgang, das Kennwort ohne Trennzeichen und groß") {
            Einladungen.identitaet("P000000001", "abcd-efgh jkmn", "beitragsrueckerstattung") shouldBe
                "668c0fe144de3d5bfb23d360ec539f0f722d7f54429ccdd85d482482b76b9105"
        }
        then("ist dasselbe Kennwort für eine andere Person oder einen anderen Vorgang eine andere Einladung") {
            val base = Einladungen.identitaet("P000000001", "ABCD-EFGH-JKMN", "beitragsrueckerstattung")
            Einladungen.identitaet("P000000002", "ABCD-EFGH-JKMN", "beitragsrueckerstattung") shouldNotBe base
            Einladungen.identitaet("P000000001", "ABCD-EFGH-JKMN", "bonusprogramm") shouldNotBe base
        }
    }
})
