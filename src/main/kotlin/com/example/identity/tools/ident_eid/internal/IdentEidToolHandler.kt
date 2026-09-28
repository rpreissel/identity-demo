package com.example.identity.tools.ident_eid.internal

import com.example.identity.contract.texts.Text
import com.example.identity.tools.ident_eid.IdentEidDescriptor
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.ClaimSource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=ident-eid. Attests what a simulated eID card shows, nothing else: first the card read
 * (possession), then a PIN (knowledge). Nobody is looked up; binding to a register person is
 * `ident-kvnr`'s act (ADR-18). Field merging and the ready-to-verify decision live in [IdentEidFlow].
 */
@Component
class IdentEidToolHandler(
    private val descriptor: IdentEidDescriptor,
    private val repository: IdentEidToolSessionRepository
) {

    /** Called directly by IdentEidToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdentEidToolSession(toolSessionId = toolSessionId))
        return outcomeFor(IdentEidState())
    }

    /**
     * A wrong PIN is bounded by the journey's attempt budget, like a real card bounds PIN
     * attempts. This run resolves no account or person to throttle against.
     */
    @Transactional
    fun patch(toolSessionId: UUID, fields: EidPatchFields): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-eid tool session: $toolSessionId" }

        val merged = IdentEidFlow.merge(data.toState(), fields)
        data.applyState(merged)
        repository.save(data)

        return when (val decision = IdentEidFlow.decide(merged)) {
            IdentEidDecision.Incomplete -> outcomeFor(merged)

            is IdentEidDecision.Verify -> {
                if (!IdentEidFlow.pinMatchesMock(decision.pinHash)) {
                    return ToolOutcome.Failed.Identification(Text("eID-PIN ungueltig"), attemptedPersonId = null)
                }
                ToolOutcome.Completed.Identified(
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    claims = listOf(
                        // Exactly what the card showed, on this procedure's own authority (ADR-18).
                        // restricted_id becomes the anchor that recognizes the Interessent on the
                        // next eid run (ADR-19).
                        Claim(AttributeType.FAMILY_NAME, checkNotNull(decision.claimed.familyName), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.GIVEN_NAMES, checkNotNull(decision.claimed.givenNames), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.BIRTH_DATE, checkNotNull(decision.claimed.birthDate).toString(), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.STREET_ADDRESS, checkNotNull(decision.claimed.streetAddress), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.POSTAL_CODE, checkNotNull(decision.claimed.postalCode), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.LOCALITY, checkNotNull(decision.claimed.locality), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.EID_RESTRICTED_ID, decision.restrictedId, ClaimSource.of(descriptor.toolId), descriptor.maxAcr)
                    ),
                    auditDetails = mapOf(
                        "provider" to "eid-mock-service",
                        "providerTxId" to "EID-$toolSessionId",
                        "methodVersion" to "1.0",
                        "evidenceHash" to IdentEidFlow.evidenceHash(
                            listOf(
                                decision.restrictedId, decision.claimed.familyName, decision.claimed.givenNames,
                                decision.claimed.birthDate.toString(), decision.claimed.streetAddress, decision.claimed.postalCode, decision.claimed.locality,
                            )
                        )
                    )
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-eid tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: IdentEidState): ToolOutcome.InProgress {
        val (step, fields) = IdentEidFlow.describe(state)
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun IdentEidToolSession.toState(): IdentEidState =
        IdentEidState(familyName, givenNames, birthDate, streetAddress, postalCode, locality, restrictedId, pinHash)

    private fun IdentEidToolSession.applyState(state: IdentEidState) {
        familyName = state.familyName
        givenNames = state.givenNames
        birthDate = state.birthDate
        streetAddress = state.streetAddress
        postalCode = state.postalCode
        locality = state.locality
        restrictedId = state.restrictedId
        pinHash = state.pinHash
    }
}
