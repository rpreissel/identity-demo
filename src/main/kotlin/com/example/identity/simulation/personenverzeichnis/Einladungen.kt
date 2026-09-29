package com.example.identity.simulation.personenverzeichnis

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.directory.InvitationEnded
import com.example.identity.contract.tool_api.directory.InvitationGrant
import com.example.identity.contract.tool_api.directory.InvitationView
import com.example.identity.contract.tool_api.directory.Invitations
import com.example.identity.simulation.personenverzeichnis.internal.Brief
import com.example.identity.simulation.personenverzeichnis.internal.BriefRepository
import com.example.identity.simulation.personenverzeichnis.internal.Einladung
import com.example.identity.simulation.personenverzeichnis.internal.EinladungRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

/** A process the register invites to. */
data class Vorgang(val id: String, val name: String)

/** An invitation as the register's page shows it - never its plaintext, which only the letter carries. */
data class EinladungView(
    val id: String,
    val personId: String,
    val vorgang: String,
    val niveau: String,
    val gueltigBis: Instant,
    val ausgestelltAm: Instant,
    val abgeschlossenAm: Instant?,
    val widerrufenAm: Instant?,
    val offen: Boolean,
)

/**
 * The register's invitations to a process (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md), built like
 * its Freischaltcodes (ADR-31): the register issues them, sends the one-time password by letter and
 * ends them - in reality through its own API or UI; `auth-invite` only asks ([Invitations]). An
 * ended invitation is announced ([InvitationEnded]), as a changed person is (ADR-34).
 *
 * The one-time password has [CODE_LENGTH] characters from [ALPHABET] (about 59 bit). The id is
 * [identitaet]: person and process in the hash make equal passwords of different invitations
 * harmless and force an attacker to guess each invitation on its own.
 */
@Service
@Transactional
class Einladungen(
    private val einladungen: EinladungRepository,
    private val briefe: BriefRepository,
    private val register: Personenverzeichnis,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : Invitations {

    private val random = SecureRandom()

    fun vorgaenge(): List<Vorgang> = VORGAENGE

    /** Stellt eine Einladung aus und verschickt den Brief; `null` für eine unbekannte Person. */
    fun ausstellen(personId: String, vorgang: String, niveau: String, gueltigBis: Instant): BriefView? {
        if (register.findPersonById(personId) == null) return null
        if (VORGAENGE.none { it.id == vorgang }) throw PersonRejectedException(Text("Zu diesem Vorgang laedt das Verzeichnis nicht ein"))
        if (niveau !in NIVEAUS) throw PersonRejectedException(Text("Niveau muss loa1 oder loa2 sein"))
        val now = clock.instant()
        if (!gueltigBis.isAfter(now)) throw PersonRejectedException(Text("Gueltig bis liegt in der Vergangenheit"))
        val code = neuesKennwort()
        val einladung = einladungen.save(
            Einladung(identitaet(personId, code, vorgang), personId, vorgang, niveau, gueltigBis, now)
        )
        return briefe.save(Brief(personId = personId, code = code, versandtAm = now, einladungId = einladung.id))
            .toBriefView(vorgang)
    }

    @Transactional(readOnly = true)
    fun fuerPerson(personId: String): List<EinladungView> {
        val now = clock.instant()
        return einladungen.findByPersonIdOrderByAusgestelltAmDesc(personId).map { it.toView(now) }
    }

    /** Der Vorgang ist erledigt: Die Einladung endet und mit ihr ihre Sitzungen. */
    fun abschliessen(id: String): EinladungView? = beenden(id) { it.abgeschlossenAm = it.abgeschlossenAm ?: clock.instant() }

    /** Das Register zieht die Einladung zurück. */
    fun widerrufen(id: String): EinladungView? = beenden(id) { it.widerrufenAm = it.widerrufenAm ?: clock.instant() }

    /** Alle Briefe, neueste zuerst, die Einmalkennwort-Briefe mit ihrem Vorgang. */
    @Transactional(readOnly = true)
    fun briefkasten(): List<BriefView> =
        briefe.findAllByOrderByIdDesc().map { brief -> brief.toBriefView(brief.einladungId?.let { einladungen.findByIdOrNull(it)?.vorgang }) }

    @Transactional(readOnly = true)
    override fun redeem(personId: String, code: String): InvitationGrant? {
        val now = clock.instant()
        val einladung = einladungen.findByPersonIdOrderByAusgestelltAmDesc(personId)
            .filter { it.istOffenAm(now) }
            .firstOrNull { gleich(checkNotNull(it.id), identitaet(personId, code, checkNotNull(it.vorgang))) }
            ?: return null
        return InvitationGrant(checkNotNull(einladung.id), checkNotNull(einladung.vorgang), AcrLevel.of(einladung.niveau))
    }

    @Transactional(readOnly = true)
    override fun find(invitation: String): InvitationView? =
        einladungen.findByIdOrNull(invitation)?.let {
            InvitationView(checkNotNull(it.id), checkNotNull(it.vorgang), checkNotNull(it.personId), it.istOffenAm(clock.instant()))
        }

    /** Beendet eine Einladung einmal; ein wiederholter Aufruf ändert nichts und meldet nichts. */
    private fun beenden(id: String, markieren: (Einladung) -> Unit): EinladungView? {
        val einladung = einladungen.findByIdOrNull(id) ?: return null
        val warBeendet = einladung.abgeschlossenAm != null || einladung.widerrufenAm != null
        markieren(einladung)
        einladungen.save(einladung)
        if (!warBeendet) events.publishEvent(InvitationEnded(id))
        return einladung.toView(clock.instant())
    }

    private fun neuesKennwort(): String =
        (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")

    private fun Einladung.toView(now: Instant) = EinladungView(
        id = checkNotNull(id), personId = checkNotNull(personId), vorgang = checkNotNull(vorgang),
        niveau = checkNotNull(niveau), gueltigBis = checkNotNull(gueltigBis), ausgestelltAm = checkNotNull(ausgestelltAm),
        abgeschlossenAm = abgeschlossenAm, widerrufenAm = widerrufenAm, offen = istOffenAm(now),
    )

    companion object {
        /** Zwölf Zeichen aus 31: rund 59 Bit, im Brief in Vierergruppen. */
        const val CODE_LENGTH = 12
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        val VORGAENGE = listOf(
            Vorgang("beitragsrueckerstattung", "Beitragsrückerstattung"),
            Vorgang("bonusprogramm", "Bonusprogramm"),
            Vorgang("adressbestaetigung", "Adressbestätigung"),
        )
        val NIVEAUS = setOf("loa1", "loa2")

        /** Trennzeichen und Groß-/Kleinschreibung zählen nicht: Was der Brief zeigt und was getippt wird, muss sich treffen. */
        fun normalisieren(code: String): String = code.filterNot { it == '-' || it.isWhitespace() }.uppercase()

        /**
         * Die eine Definition der Einladungs-Id, die das Fachsystem genauso bildet: SHA-256 in
         * Kleinbuchstaben-Hex über `personId:KENNWORT:vorgang`, das Kennwort ohne Trennzeichen und groß.
         */
        fun identitaet(personId: String, code: String, vorgang: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$personId:${normalisieren(code)}:$vorgang".toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun gleich(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
