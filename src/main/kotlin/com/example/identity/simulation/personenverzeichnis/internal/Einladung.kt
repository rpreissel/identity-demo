package com.example.identity.simulation.personenverzeichnis.internal

import com.example.identity.contract.tool_api.values.PartnerNumber
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * An invitation to one process (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Its id is SHA-256
 * over person, one-time password and process; the password itself stands only in the letter.
 */
@Entity
@Table(schema = "personenverzeichnis", name = "einladung")
class Einladung(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String? = null,
    @Column(name = "person_id", nullable = false, length = 10)
    var personId: PartnerNumber? = null,
    @Column(name = "vorgang", nullable = false, length = 64)
    var vorgang: String? = null,
    @Column(name = "niveau", nullable = false, length = 16)
    var niveau: String? = null,
    @Column(name = "gueltig_bis", nullable = false)
    var gueltigBis: Instant? = null,
    @Column(name = "ausgestellt_am", nullable = false)
    var ausgestelltAm: Instant? = null,
) {
    @Column(name = "abgeschlossen_am")
    var abgeschlossenAm: Instant? = null

    @Column(name = "widerrufen_am")
    var widerrufenAm: Instant? = null

    /** Weder abgeschlossen noch widerrufen noch abgelaufen. */
    fun istOffenAm(now: Instant): Boolean =
        abgeschlossenAm == null && widerrufenAm == null && now < checkNotNull(gueltigBis)
}

interface EinladungRepository : JpaRepository<Einladung, String> {
    fun findByPersonIdOrderByAusgestelltAmDesc(personId: PartnerNumber): List<Einladung>
    fun findAllByOrderByAusgestelltAmDesc(): List<Einladung>
}
