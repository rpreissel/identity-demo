package com.example.identity.tools.ident_eid

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.DemoOnly
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.ClaimSource
import org.springframework.stereotype.Component

/** Its own constant, like in every other module, although ident-eid is the only tool for "eid". */
internal const val EID_METHOD = "eid"

/**
 * Self-description for toolId=ident-eid. Mock eID: reads a simulated card (possession) plus a PIN
 * (knowledge) in one run, hence `maxAcr=loa3` and both factor types. It resolves nobody: binding
 * the attested identity to a register person is `ident-kvnr`'s separate act (ADR-18).
 */
@Component
object IdentEidDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-eid")
    override val role = ToolRole.IDENTIFICATION
    override val method = EID_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA3
    override val demoOnly = SIMULATED_EID
    // Exactly what the card carries, on this procedure's own authority. No PERSON_ID and no KVNR:
    // a real eID card holds neither (ADR-18). restricted_id is the card's pseudonym, a replaceable
    // local anchor, so a later eid run recognizes the prospect it created (ADR-19).
    override val claims = setOf(
        ClaimDeclaration(AttributeType.FAMILY_NAME, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.GIVEN_NAMES, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.BIRTH_DATE, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.STREET_ADDRESS, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.POSTAL_CODE, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.LOCALITY, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.EID_RESTRICTED_ID, ClaimSource.of(toolId))
    )
}

/** ADR-36: the level rests on a simulated counterpart, not on anything this instance can check. */
private val SIMULATED_EID = DemoOnly(
    "Die eID-Kartenlesung ist simuliert; ein echtes Ergebnis kommt serverseitig vom eID-Server"
)
