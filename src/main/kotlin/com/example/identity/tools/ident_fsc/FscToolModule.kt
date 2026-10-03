package com.example.identity.tools.ident_fsc

import com.example.identity.contract.tool_api.FactorType.POSSESSION
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

internal const val IDENT_FSC_TOOL_ID = "ident-fsc"

/**
 * `ident-fsc` (docs/03-tool-architektur.md #1): identification with the Freischaltcode the
 * Personenverzeichnis sent by letter. Every value is checked against the register, which is the
 * source; this tool is only its channel.
 */
internal val FscModule = toolModule(
    method = "fsc",
    proves = factors(POSSESSION, upTo = AcrLevel.LOA2),
)

internal val IdentFsc = FscModule.identify(
    IDENT_FSC_TOOL_ID,
    also = setOf(AttributeType.PERSON_ID, AttributeType.KVNR, AttributeType.MEMBER_NUMBER),
    vouchedBy = ClaimSource.PERSON_DIRECTORY,
)

/**
 * A method module talks to the orchestrator through tool_api only, never to account or another
 * method module (docs/03-tool-architektur.md #2). Its controllers (`ident_fsc.api.v1`) reach the
 * orchestrator through `tool_api` alone (docs/04-orchestrierung.md #5).
 *
 * The register is reached over ports only ([com.example.identity.contract.tool_api.directory.PersonDirectory],
 * [com.example.identity.contract.tool_api.directory.ActivationCodes], ADR-31). Its own classes speak its language.
 */
@ApplicationModule(id = "ident_fsc", allowedDependencies = ["tool_api", "texts"])
@Configuration
internal class FscToolModule {
    @Bean
    fun fscModule() = FscModule
}
