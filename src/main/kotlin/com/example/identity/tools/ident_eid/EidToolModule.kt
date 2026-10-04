package com.example.identity.tools.ident_eid

import com.example.identity.contract.texts.Text
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
 * Card-bound pseudonym from the eID read (stand-in for the real "Restricted Identifier"). It changes
 * with a new card but never moves to another person, so it recognizes an eid-identified prospect: a
 * replaceable local account anchor (ADR-19), compared as written. The register never stores it.
 */
internal val EID_RESTRICTED_ID = AttributeType.anchor(
    "restricted_id", AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = false,
    caseSensitive = true, normalize = { it.trim() },
)

internal const val IDENT_EID_TOOL_ID = "ident-eid"

/**
 * `ident-eid` (docs/verfahren/eid.md). Mock eID: reads a simulated card (possession) plus a
 * PIN (knowledge) in one run, hence loa3 and both factor types. It resolves nobody: binding the
 * attested identity to a register person is `ident-kvnr`'s separate act (ADR-18). It asserts exactly
 * what the card carries, on its own authority: no PERSON_ID and no KVNR, which a real eID card holds
 * neither. `restricted_id` is the card's pseudonym, a replaceable local anchor, so a later eid run
 * recognizes the prospect it created (ADR-19).
 */
internal val EidModule = toolModule(
    method = "eid",
    name = Text("eID"),
    proves = factors(POSSESSION, KNOWLEDGE, upTo = AcrLevel.LOA3),
    demoOnly = "Die eID-Kartenlesung ist simuliert; ein echtes Ergebnis kommt serverseitig vom eID-Server",
)

internal val IdentEid = EidModule.identify(
    IDENT_EID_TOOL_ID,
    versions = setOf(1),
    hint = Text("Online-Ausweisfunktion (simuliert)"),
    also = setOf(AttributeType.STREET_ADDRESS, AttributeType.POSTAL_CODE, AttributeType.LOCALITY, EID_RESTRICTED_ID),
)

/**
 * A method module talks to the orchestrator through tool_api only, never to account or another
 * method module (docs/03-tool-architektur.md #7). Its controllers (`ident_eid.api.v1`) reach the
 * orchestrator through `tool_api` alone (docs/04-orchestrierung.md #8).
 */
@ApplicationModule(id = "ident_eid", allowedDependencies = ["tool_api", "texts"])
@Configuration
internal class EidToolModule {
    @Bean
    fun eidModule() = EidModule
}
