package com.example.identity.tools.ident_nect

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.DemoOnly
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import org.springframework.stereotype.Component

internal const val NECT_METHOD = "nect"

/**
 * Self-description for toolId=ident-nect. The user picks a document at Nect (eID, ePass or EUDI
 * wallet), so [maxAcr], [factorTypes] and the claims are the union over all three; each run reports
 * what its document proved. Like `ident-eid` it attests and resolves nobody (ADR-18).
 */
@Component
object IdentNectDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-nect")
    override val role = MethodRole.IDENTIFICATION
    override val method = NECT_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA3
    override val demoOnly = SIMULATED_NECT
    // Nothing is typed here: the run opens on "go to Nect".
    override val startStep = "redirect"
    override val claims = setOf(
        ClaimDeclaration(AttributeType.FAMILY_NAME, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.GIVEN_NAMES, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.BIRTH_DATE, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.STREET_ADDRESS, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.POSTAL_CODE, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.LOCALITY, ClaimSource.of(toolId)),
        // eID via Nect yields Nect's own card pseudonym, never the one ident-eid reads - the
        // pseudonym is specific to the service provider (§18 PAuswG). Hence its own anchor (ADR-19).
        ClaimDeclaration(AttributeType.NECT_RESTRICTED_ID, ClaimSource.of(toolId))
    )
}

/** ADR-36: the level rests on a simulated counterpart, not on anything this instance can check. */
private val SIMULATED_NECT = DemoOnly(
    "Die Nect-Gegenstelle ist simuliert (nect); ein echtes Ergebnis kommt serverseitig von Nect"
)
