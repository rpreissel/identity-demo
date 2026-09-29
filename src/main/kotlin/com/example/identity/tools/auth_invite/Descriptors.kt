package com.example.identity.tools.auth_invite

import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.AcrLevel
import org.springframework.stereotype.Component

internal const val INVITE_METHOD = "invite"

/**
 * toolId=auth-invite: KVNR or Partnernummer plus the one-time password from the letter. Possession
 * of the letter, like the Freischaltcode (ADR-31); the level is the one the invitation carries, at
 * most loa2. A lookup tool that resolves an invitation instead of an account: the session's subject
 * is then the invitation (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
 */
@Component
object AuthInviteDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-invite")
    override val method = INVITE_METHOD
    override val role = MethodRole.LOOKUP_AUTH
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = AcrLevel.LOA2
}
