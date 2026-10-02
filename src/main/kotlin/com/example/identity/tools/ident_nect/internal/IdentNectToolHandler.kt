package com.example.identity.tools.ident_nect.internal

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.ident_nect.NECT_RESTRICTED_ID
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.tools.ident_nect.NectModule
import com.example.identity.contract.texts.Text
import com.example.identity.tools.ident_nect.api.v1.NectRedirectStep
import com.example.identity.simulation.nect.NectAttribute
import com.example.identity.simulation.nect.NectAttributes
import com.example.identity.simulation.nect.NectFailure
import com.example.identity.simulation.nect.NectIdent
import com.example.identity.simulation.nect.NectProcedure
import com.example.identity.simulation.nect.NectResult
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Where Nect sends the user back to when the channel names no address: the app channel picks
 * `nectCaseId` up from its URL. The web channel names Keycloak's action URL instead (docs/adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md).
 */
internal const val NECT_CALLBACK_URI = "/app/"

/**
 * What we ask Nect for, the same as `ident-eid` reads from the card. Nect hands on what the chosen
 * document can deliver: a passport has no address, a wallet PID no pseudonym.
 */
internal val NECT_REQUESTED = setOf(
    NectAttribute.FAMILY_NAME,
    NectAttribute.GIVEN_NAMES,
    NectAttribute.BIRTH_DATE,
    NectAttribute.ADDRESS,
    NectAttribute.EID_PSEUDONYM,
    // Not DOCUMENT_ID: a document number may not be kept (§ 20 PAuswG), so it is not asked for.
)

/**
 * toolId=ident-nect. Opens a case at Nect; the client reports back the case id. The result never
 * travels through the client: it is redeemed from Nect server-side, once, and only for the case
 * this tool session opened. Each run reports the level and factors of the chosen document (ADR-18).
 *
 * The return address belongs to the case: the channel names it at the start (the web channel its
 * Keycloak action URL, the app nothing), and a retry reuses it. Only an address under one of the
 * configured prefixes is accepted - a channel may not send the user anywhere else.
 */
@Component
class IdentNectToolHandler(
    private val repository: IdNectToolSessionRepository,
    private val nect: NectIdent,
    private val clock: Clock,
    private val properties: IdentNectProperties = IdentNectProperties()
) {

    /** [returnUri] is where Nect sends the user back to; null means the app channel's `/app/`. */
    @Transactional
    fun start(toolSessionId: ToolSessionId, returnUri: String? = null): ToolOutcome {
        val callbackUri = acceptedReturnUri(returnUri)
        val case = nect.createCase(callbackUri, NECT_REQUESTED)
        repository.save(IdNectToolSession(toolSessionId = toolSessionId, caseId = case.caseId, returnUri = returnUri, createdAt = clock.instant()))
        return redirect(case.caseId, case.jumpUrl)
    }

    /**
     * [retry] opens a fresh case - the old one may be spent or abandoned. It goes back to [returnUri]
     * if given (the web channel's action code is single-use, docs/adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md),
     * else to the address from the activation.
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, caseId: UUID?, retry: Boolean, returnUri: String? = null): ToolOutcome {
        val data = checkNotNull(repository.findByToolSessionId(toolSessionId)) { "Unknown ident-nect tool session: $toolSessionId" }
        if (retry) {
            if (returnUri != null) data.returnUri = acceptedReturnUri(returnUri)
            val case = nect.createCase(data.returnUri ?: NECT_CALLBACK_URI, NECT_REQUESTED)
            data.caseId = case.caseId
            repository.save(data)
            return redirect(case.caseId, case.jumpUrl)
        }
        if (caseId == null || caseId != data.caseId) {
            return ToolOutcome.Failed.Identification(Text("Nect-Vorgang gehört nicht zu diesem Ablauf"), attemptedPersonId = null)
        }
        return when (val result = nect.redeem(caseId)) {
            null -> ToolOutcome.Failed.Identification(Text("Nect-Vorgang unbekannt oder bereits eingelöst"), attemptedPersonId = null)
            NectResult.Open -> ToolOutcome.Failed.Identification(Text("Nect-Vorgang noch nicht abgeschlossen"), attemptedPersonId = null)
            NectResult.Cancelled -> ToolOutcome.Failed.Identification(Text("Identifizierung bei Nect abgebrochen"), attemptedPersonId = null)
            is NectResult.Failed -> ToolOutcome.Failed.Identification(
                when (result.reason) {
                    NectFailure.PASSPORT_EXPIRED -> Text("Nect: Der Reisepass ist abgelaufen")
                    NectFailure.SELFIE_MISMATCH -> Text("Nect: Das Selfie passt nicht zum Passbild")
                    NectFailure.SIMULATED -> Text("Nect: Identifizierung fehlgeschlagen")
                },
                // Nect names nobody when it fails - there is no person to charge.
                attemptedPersonId = null
            )
            is NectResult.Identified -> identified(toolSessionId, caseId, result)
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        val data = checkNotNull(repository.findByToolSessionId(toolSessionId)) { "Unknown ident-nect tool session: $toolSessionId" }
        val caseId = checkNotNull(data.caseId)
        return redirect(caseId, nect.jumpUrl(caseId))
    }

    /** `require`: a rejected address is the caller's mistake, answered with 400, never a bug. */
    private fun acceptedReturnUri(returnUri: String?): String {
        if (returnUri == null) return NECT_CALLBACK_URI
        require(properties.returnUriPrefixes.any { returnUri.startsWith(it) }) {
            "ident-nect: return URI outside the configured prefixes"
        }
        return returnUri
    }

    private fun redirect(caseId: UUID, jumpUrl: String) =
        ToolOutcome.InProgress(nextStep = "redirect", stepData = NectRedirectStep(jumpUrl = jumpUrl, caseId = caseId))

    private fun identified(toolSessionId: ToolSessionId, caseId: UUID, result: NectResult.Identified): ToolOutcome.Completed.Identified {
        val level = levelOf(result.procedure)
        val a = result.attributes
        val source = NectModule.source(ToolRole.IDENTIFICATION)
        val values = listOfNotNull(
            a.name?.let { AttributeType.FAMILY_NAME to it },
            a.vorname?.let { AttributeType.GIVEN_NAMES to it },
            a.geburtsdatum?.let { AttributeType.BIRTH_DATE to it.toString() },
            a.strasse?.let { AttributeType.STREET_ADDRESS to it },
            a.plz?.let { AttributeType.POSTAL_CODE to it },
            a.ort?.let { AttributeType.LOCALITY to it },
            // Only the eID chip carries a card pseudonym, and read by Nect it is Nect's own
            // (§18 PAuswG) - its own anchor, never the one ident-eid writes (ADR-19).
            a.restrictedId?.takeIf { result.procedure == NectProcedure.EID }?.let { NECT_RESTRICTED_ID to it }
        )
        return ToolOutcome.Completed.Identified(
            amr = listOf("nect-${result.procedure.wireName}"),
            achievedAcr = level,
            factorTypes = factorsOf(result.procedure),
            claims = values.map { (type, value) -> Claim(type, value, source, level) },
            auditDetails = auditOf(toolSessionId, caseId, result.procedure, a)
        )
    }

    private fun auditOf(toolSessionId: ToolSessionId, caseId: UUID, procedure: NectProcedure, a: NectAttributes): Map<String, String> =
        buildMap {
            put("provider", "nect-mock")
            put("providerTxId", caseId.toString())
            put("toolSessionId", toolSessionId.toString())
            put("procedure", procedure.wireName)
            // No document number: it may not be kept (§ 20 PAuswG), and the case id already
            // lets Nect answer for this run.
        }

    private companion object {
        // A passport read (chip + selfie match) proves the document but not the holder the way
        // an eID PIN or a wallet's PID binding does - hence substantial, not high.
        fun levelOf(procedure: NectProcedure): AcrLevel = when (procedure) {
            NectProcedure.EID -> AcrLevel.LOA3
            NectProcedure.EPASS -> AcrLevel.LOA2
            NectProcedure.EUDI -> AcrLevel.LOA3
        }

        fun factorsOf(procedure: NectProcedure): Set<FactorType> = when (procedure) {
            NectProcedure.EID -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            NectProcedure.EPASS -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
            NectProcedure.EUDI -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        }
    }
}
