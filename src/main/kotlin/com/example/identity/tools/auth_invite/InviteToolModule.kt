package com.example.identity.tools.auth_invite

import com.example.identity.contract.tool_api.FactorType.POSSESSION
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import com.example.identity.contract.tool_api.lookupLogin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

internal const val AUTH_INVITE_LOOKUP_TOOL_ID = "auth-invite-lookup"

/**
 * `auth-invite-lookup`: KVNR or Partnernummer plus the one-time password from the letter.
 * Possession of the letter, like the Freischaltcode (ADR-31); the level is the one the invitation
 * carries, at most loa2. A lookup tool that resolves an invitation instead of an account: the
 * session's subject is then the invitation (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
 */
internal val InviteModule = toolModule(
    method = "invite",
    proves = factors(POSSESSION, upTo = AcrLevel.LOA2),
    tools = listOf(
        lookupLogin(AUTH_INVITE_LOOKUP_TOOL_ID),
    ),
)

/**
 * Process access by one-time password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Like
 * every method module it talks to the orchestrator through tool_api only; the person register and
 * the register's invitations are reached over ports
 * ([com.example.identity.contract.tool_api.directory.PersonDirectory],
 * [com.example.identity.contract.tool_api.directory.Invitations]).
 */
@ApplicationModule(id = "auth_invite", allowedDependencies = ["tool_api", "texts"])
@Configuration
internal class InviteToolModule {
    @Bean
    fun inviteModule() = InviteModule
}
