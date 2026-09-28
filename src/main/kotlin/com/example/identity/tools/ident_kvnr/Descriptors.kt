package com.example.identity.tools.ident_kvnr

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.TrustLevel
import org.springframework.stereotype.Component

/** Its own constant, like in every other module, although ident-kvnr is the only tool for "kvnr". */
internal const val KVNR_METHOD = "kvnr"

/**
 * Self-description for toolId=ident-kvnr, the correlation half of an identification (ADR-18): is
 * the attested person in the register? [claims] rest on `PERSON_DIRECTORY`, which supplies both
 * values. [maxAcr] is the weight of the written anchor, not a level this run proves; as a
 * [MethodRole.CORRELATION] it leaves no session evidence. Its assurance comes from [requires] and
 * the identity match before the anchor is written.
 */
@Component
object IdentKvnrDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-kvnr")
    override val role = MethodRole.CORRELATION
    override val method = KVNR_METHOD
    override val factorTypes = emptySet<com.example.identity.contract.tool_api.FactorType>()
    override val maxAcr = AcrLevel.LOA2
    override val claims = setOf(
        ClaimDeclaration(AttributeType.PERSON_ID, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.KVNR, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.INSURANCE_NUMBER, ClaimSource.PERSON_DIRECTORY)
    )

    // Only offerable once an attestation established who the subject is - there must be
    // something to match the register's person against, or this would degrade into "type any
    // KVNR and own that person". Deliberately expressed as the attributes themselves, not as
    // "ident-eid must have run": an EUDI wallet attesting the same three satisfies it unchanged.
    override val requires = setOf(
        ClaimRequirement(AttributeType.FAMILY_NAME, TrustLevel.PROVEN),
        ClaimRequirement(AttributeType.GIVEN_NAMES, TrustLevel.PROVEN),
        ClaimRequirement(AttributeType.BIRTH_DATE, TrustLevel.PROVEN)
    )
}
