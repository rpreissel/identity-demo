package com.example.identity.simulation.personenverzeichnis

import com.example.identity.simulation.personenverzeichnis.internal.Brief
import com.example.identity.simulation.personenverzeichnis.internal.BriefRepository
import com.example.identity.simulation.personenverzeichnis.internal.Freischaltcode
import com.example.identity.simulation.personenverzeichnis.internal.FreischaltcodeRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.example.identity.contract.tool_api.directory.ActivationCodes
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

/** A Freischaltcode as the register shows it - never its plaintext, which only the [BriefView] carries. */
data class FreischaltcodeView(
    val id: Long,
    val personId: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val valid: Boolean
)

/** What a letter carries: a Freischaltcode or a one-time password for a process. */
enum class BriefArt { FREISCHALTCODE, EINMALKENNWORT }

/** A simulated letter from the register's mailbox, plaintext code included. */
data class BriefView(
    val id: Long,
    val personId: String,
    val code: String,
    val versandtAm: Instant,
    val art: BriefArt,
    /** Only for a Freischaltcode letter. */
    val freischaltcodeId: Long? = null,
    /** Only for a one-time password letter: the invitation, and the process it is for. */
    val einladungId: String? = null,
    val vorgang: String? = null,
)

/**
 * The register's Freischaltcode service (ADR-31). The register issues the codes and sends them by
 * letter; `ident_fsc` only asks [pruefe]. The issuing side ([ausstellen], [widerrufen], the listings)
 * serves the register's mock UI (`/personenverzeichnis/`) and the demo disclosure.
 */
@Service
class Freischaltcodes(
    private val codes: FreischaltcodeRepository,
    private val briefe: BriefRepository,
    private val clock: Clock,
) : ActivationCodes {

    private val random = SecureRandom()

    // ------------------------------------------------------------------ verification side

    /** The port's side ([ActivationCodes]) - the register's own words stay inside. */
    override fun digest(code: String): String = hash(code)

    override fun isValid(personId: String, codeDigest: String): Boolean = pruefe(personId, codeDigest)

    /** Whether [codeHash] (see [digest]) is a currently valid Freischaltcode of [personId]. */
    @Transactional(readOnly = true)
    fun pruefe(personId: String, codeHash: String): Boolean {
        val now = clock.instant()
        return codes.findByPersonIdAndCodeHash(personId, codeHash).any { it.isValidAt(now) }
    }

    // ------------------------------------------------------------------ issuing side

    /** Issues a new code for [personId] and "sends" it: the returned letter is the only plaintext. */
    @Transactional
    fun ausstellen(personId: String, gueltigBis: Instant): BriefView {
        val code = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        val stored = codes.save(Freischaltcode(personId = personId, codeHash = hash(code), expiresAt = gueltigBis))
        return briefe.save(
            Brief(personId = personId, freischaltcodeId = stored.id, code = code, versandtAm = clock.instant())
        ).toView()
    }

    /** @return false when no such code exists. Revoking twice keeps the first revocation time. */
    @Transactional
    fun widerrufen(freischaltcodeId: Long): Boolean {
        val code = codes.findByIdOrNull(freischaltcodeId) ?: return false
        if (code.revokedAt == null) code.revokedAt = clock.instant()
        return true
    }

    @Transactional(readOnly = true)
    fun fuerPerson(personId: String): List<FreischaltcodeView> {
        val now = clock.instant()
        return codes.findByPersonIdOrderByIdDesc(personId).map { it.toView(now) }
    }

    @Transactional(readOnly = true)
    fun briefkasten(): List<BriefView> = briefe.findAllByOrderByIdDesc().map { it.toView() }

    /**
     * The plaintext of the newest letter whose code is still valid - what the demo pre-fills, read
     * from the mailbox instead of a second hard-coded list.
     */
    @Transactional(readOnly = true)
    fun juengsterGueltigerCode(personId: String): String? {
        val now = clock.instant()
        val valid = codes.findByPersonIdOrderByIdDesc(personId).filter { it.isValidAt(now) }.mapNotNull { it.id }.toSet()
        return briefe.findByPersonIdOrderByIdDesc(personId).firstOrNull { it.freischaltcodeId in valid }?.code
    }

    private fun Freischaltcode.toView(now: Instant) =
        FreischaltcodeView(checkNotNull(id), checkNotNull(personId), checkNotNull(expiresAt), revokedAt, isValidAt(now))

    private fun Brief.toView() = toBriefView()

    companion object {
        /** Unambiguous characters only - no 0/O, 1/I/L - since the code is read off a letter. */
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** The one definition of how a Freischaltcode is digested - the seed SQL mirrors it. */
        fun hash(code: String): String =
            MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal fun Brief.toBriefView(vorgang: String? = null) = BriefView(
    id = checkNotNull(id),
    personId = checkNotNull(personId),
    code = checkNotNull(code),
    versandtAm = checkNotNull(versandtAm),
    art = if (einladungId != null) BriefArt.EINMALKENNWORT else BriefArt.FREISCHALTCODE,
    freischaltcodeId = freischaltcodeId,
    einladungId = einladungId,
    vorgang = vorgang,
)
