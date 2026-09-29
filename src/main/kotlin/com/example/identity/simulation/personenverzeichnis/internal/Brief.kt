package com.example.identity.simulation.personenverzeichnis.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The simulated letter that carries a code in plaintext to its person: a Freischaltcode (ADR-31) or
 * a one-time password for a process (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md), never both.
 */
@Entity
@Table(schema = "personenverzeichnis", name = "brief")
class Brief(
    @Column(name = "person_id", nullable = false, length = 10)
    var personId: String? = null,

    /** Only for a Freischaltcode letter. */
    @Column(name = "freischaltcode_id")
    var freischaltcodeId: Long? = null,

    @Column(name = "code", nullable = false, length = 64)
    var code: String? = null,

    @Column(name = "versandt_am", nullable = false)
    var versandtAm: Instant? = null,

    /** Only for a one-time password letter (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). */
    @Column(name = "einladung_id", length = 64)
    var einladungId: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
