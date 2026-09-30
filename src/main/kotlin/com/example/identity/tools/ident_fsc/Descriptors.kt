package com.example.identity.tools.ident_fsc

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.ClaimSource
import org.springframework.stereotype.Component

/** Its own constant, like in every other module, although ident-fsc is the only tool for "fsc". */
internal const val FSC_METHOD = "fsc"

/** Self-description for toolId=ident-fsc (docs/03-tool-architektur.md #1). */
@Component
object IdentFscDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-fsc")
    override val role = ToolRole.IDENTIFICATION
    override val method = FSC_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = AcrLevel.LOA2
    // ClaimSource.PERSON_DIRECTORY: every value is checked against the register, which is the
    // source; this tool is only its channel.
    override val claims = setOf(
        ClaimDeclaration(AttributeType.PERSON_ID, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.KVNR, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.MEMBER_NUMBER, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.FAMILY_NAME, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.GIVEN_NAMES, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.BIRTH_DATE, ClaimSource.PERSON_DIRECTORY)
    )
}
