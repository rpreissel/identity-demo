package com.example.identity.simulation.personenverzeichnis.api.v1

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.demo.demo_mode.DemoSurface
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.http.HttpHeaders
import com.example.identity.contract.texts.TextBundle
import com.example.identity.contract.texts.Text
import com.example.identity.simulation.personenverzeichnis.BriefView
import com.example.identity.simulation.personenverzeichnis.EinladungView
import com.example.identity.simulation.personenverzeichnis.Einladungen
import com.example.identity.simulation.personenverzeichnis.Vorgang
import com.example.identity.simulation.personenverzeichnis.Personenverzeichnis
import com.example.identity.simulation.personenverzeichnis.Freischaltcodes
import com.example.identity.simulation.personenverzeichnis.FreischaltcodeView
import com.example.identity.simulation.personenverzeichnis.PersonData
import com.example.identity.simulation.personenverzeichnis.PersonRejectedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class FreischaltcodeAusstellenRequest(val gueltigBis: Instant)

data class EinladungAusstellenRequest(val vorgang: String, val niveau: String, val gueltigBis: Instant)

/**
 * The register's management face for the `/personenverzeichnis/` page, a stand-in for the real
 * register's operator UI. Not under `/orchestrator`, because it is the foreign system, and without
 * login, because our admin rights do not reach into it. Persons cannot be deleted: accounts refer
 * to a person id, and a register forgetting a person is a case of its own.
 */
@RestController
@DemoSurface
@RequestMapping("/mock-personenverzeichnis")
@Tag(name = "Mock Personenverzeichnis", description = "Simuliertes Personenverzeichnis - kein Endpunkt dieser Anwendung, sondern das Fremdsystem")
class PersonenverzeichnisController(
    private val register: Personenverzeichnis,
    private val freischaltcodes: Freischaltcodes,
    private val einladungen: Einladungen,
) {

    @GetMapping("personen")
    @Operation(summary = "Alle Personen im Register")
    fun personen(): List<PersonData> = register.allePersonen()

    @PostMapping("personen")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Person anlegen",
        description = "Das Verzeichnis vergibt die Partnernummer. Versicherungsnummer (acht Ziffern) und KVNR (ein Buchstabe, " +
            "neun Ziffern) sind optional und eindeutig; eine KVNR nur zusammen mit einer Versicherungsnummer."
    )
    fun anlegen(@RequestBody person: PersonData): PersonData = register.anlegen(person)

    @PutMapping("personen/{personId}")
    @Operation(summary = "Person ändern", description = "Alles außer der Partnernummer; Regeln wie beim Anlegen. Änderungen gehen als Ereignis an die Konten (ADR-34).")
    fun aendern(@PathVariable personId: PartnerNumber, @RequestBody person: PersonData): ResponseEntity<PersonData> =
        register.aendern(personId, person)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("personen/{personId}/freischaltcodes")
    @Operation(summary = "Freischaltcodes einer Person", description = "Ohne Klartext - den trägt nur der Brief.")
    fun freischaltcodes(@PathVariable personId: PartnerNumber): List<FreischaltcodeView> = freischaltcodes.fuerPerson(personId)

    @PostMapping("personen/{personId}/freischaltcodes")
    @Operation(summary = "Freischaltcode ausstellen", description = "Antwortet mit dem Brief, der den Klartext trägt.")
    fun ausstellen(@PathVariable personId: PartnerNumber, @RequestBody request: FreischaltcodeAusstellenRequest): ResponseEntity<BriefView> {
        if (register.findPersonById(personId) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.status(HttpStatus.CREATED).body(freischaltcodes.ausstellen(personId, request.gueltigBis))
    }

    @DeleteMapping("freischaltcodes/{freischaltcodeId}")
    @Operation(summary = "Freischaltcode widerrufen")
    fun widerrufen(@PathVariable freischaltcodeId: Long): ResponseEntity<Void> =
        if (freischaltcodes.widerrufen(freischaltcodeId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    @GetMapping("vorgaenge")
    @Operation(summary = "Vorgänge, zu denen das Verzeichnis einlädt")
    fun vorgaenge(): List<Vorgang> = einladungen.vorgaenge()

    @GetMapping("personen/{personId}/einladungen")
    @Operation(summary = "Einladungen einer Person", description = "Ohne Klartext - den trägt nur der Brief (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).")
    fun einladungenDerPerson(@PathVariable personId: PartnerNumber): List<EinladungView> = einladungen.fuerPerson(personId)

    @PostMapping("personen/{personId}/einladungen")
    @Operation(summary = "Einladung mit Einmalkennwort ausstellen", description = "Antwortet mit dem Brief, der das Einmalkennwort im Klartext trägt.")
    fun einladungAusstellen(@PathVariable personId: PartnerNumber, @RequestBody request: EinladungAusstellenRequest): ResponseEntity<BriefView> =
        einladungen.ausstellen(personId, request.vorgang, request.niveau, request.gueltigBis)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    @PostMapping("einladungen/{einladungId}/abschluss")
    @Operation(
        summary = "Vorgang abschließen",
        description = "Das Fachsystem meldet den Vorgang als erledigt, mit der Id der Einladung (dem Hash). Die Einladung endet und mit ihr ihre Sitzungen."
    )
    fun einladungAbschliessen(@PathVariable einladungId: String): ResponseEntity<EinladungView> =
        einladungen.abschliessen(einladungId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @DeleteMapping("einladungen/{einladungId}")
    @Operation(summary = "Einladung widerrufen")
    fun einladungWiderrufen(@PathVariable einladungId: String): ResponseEntity<Void> =
        if (einladungen.widerrufen(einladungId) != null) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    @GetMapping("briefe")
    @Operation(summary = "Briefkasten", description = "Alle verschickten Briefe, neueste zuerst.")
    fun briefe(): List<BriefView> = einladungen.briefkasten()

    /** The register answers for itself; its refusals are not this application's error contract. */
    @ExceptionHandler(PersonRejectedException::class)
    fun rejected(exception: PersonRejectedException): ResponseEntity<Map<String, Text>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to exception.text))

    /**
     * This service's own texts in [lang], for its own page - a foreign system brings its wordings
     * along (docs/adr/ADR-033). ETag/If-None-Match: 304 while the client's copy is current.
     */
    @GetMapping("texts/{lang}")
    @Operation(summary = "Texte des Dienstes in einer Sprache (mit ETag)")
    fun texts(
        @PathVariable lang: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?
    ): ResponseEntity<Map<String, String>> = TEXTS.respond(lang, ifNoneMatch)

    private companion object {
        val TEXTS = TextBundle("personenverzeichnis")
    }
}
