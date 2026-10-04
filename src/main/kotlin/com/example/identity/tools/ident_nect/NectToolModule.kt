package com.example.identity.tools.ident_nect

import com.example.identity.contract.texts.Text
import com.example.identity.tools.ident_nect.api.v1.NectStepData
import com.example.identity.contract.tool_api.FactorType.INHERENCE
import com.example.identity.contract.tool_api.FactorType.KNOWLEDGE
import com.example.identity.contract.tool_api.FactorType.POSSESSION
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AnchorAcrFloor
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

/**
 * The card pseudonym from an eID read through Nect. The pseudonym is specific to card and service
 * provider (§18 PAuswG), so it never equals the one `ident-eid` reads for the same card. Its own
 * anchor with the same rules; neither kind of run overwrites the other's anchor.
 */
internal val NECT_RESTRICTED_ID = AttributeType.anchor(
    "nect_restricted_id", AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = false,
    caseSensitive = true, normalize = { it.trim() },
)

internal const val IDENT_NECT_TOOL_ID = "ident-nect"

/**
 * `ident-nect` (docs/03-tool-architektur.md #1). The user picks a document at Nect (eID, ePass or
 * EUDI wallet), so level, factors and claims are the union over all three; each run reports what its
 * document proved. Like `ident-eid` it attests and resolves nobody (ADR-18). Nothing is typed here:
 * the run opens on "go to Nect". eID via Nect yields Nect's own card pseudonym, never the one
 * ident-eid reads - the pseudonym is specific to the service provider (§18 PAuswG), hence its own
 * anchor (ADR-19).
 */
internal val NectModule = toolModule(
    method = "nect",
    name = Text("Nect"),
    proves = factors(POSSESSION, KNOWLEDGE, INHERENCE, upTo = AcrLevel.LOA3),
    demoOnly = "Die Nect-Gegenstelle ist simuliert (nect); ein echtes Ergebnis kommt serverseitig von Nect",
    stepData = NectStepData,
)

internal val IdentNect = NectModule.identify(
    IDENT_NECT_TOOL_ID,
    versions = setOf(1),
    hint = Text("Ausweis, Reisepass oder EUDI-Wallet bei Nect (simuliert)"),
    also = setOf(AttributeType.STREET_ADDRESS, AttributeType.POSTAL_CODE, AttributeType.LOCALITY, NECT_RESTRICTED_ID),
    startStep = "redirect",
)

/**
 * Identification through Nect (docs/03-tool-architektur.md, ident-nect). Besides tool_api it
 * declares one edge to the identification service itself (`nect.NectIdent`), like
 * `auth_kobil -> kobil` (ADR-31). Swapping in the real service changes that edge, not the tool.
 */
@ApplicationModule(id = "ident_nect", allowedDependencies = ["tool_api", "nect", "texts"])
@Configuration
internal class NectToolModule {
    @Bean
    fun nectModule() = NectModule
}
