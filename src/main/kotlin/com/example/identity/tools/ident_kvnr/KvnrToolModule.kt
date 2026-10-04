package com.example.identity.tools.ident_kvnr

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.ClaimTrust
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

internal const val IDENT_KVNR_TOOL_ID = "ident-kvnr"

/**
 * `ident-kvnr`, the correlation half of an identification (ADR-18): is the attested person in the
 * register? Its claims rest on the Personenverzeichnis, which supplies the values. The level is the
 * weight of the written anchor, not one this run proves; as a correlation it leaves no session
 * evidence. Its assurance comes from `requires` and the identity match before the anchor is written:
 * offerable only once an attestation established who the subject is, expressed as the attributes
 * themselves, not as "ident-eid must have run", so an EUDI wallet attesting the same three satisfies
 * it unchanged.
 */
internal val KvnrModule = toolModule(
    method = "kvnr",
    name = Text("Versichertennummer"),
    proves = factors(upTo = AcrLevel.LOA2),
)

internal val IdentKvnr = KvnrModule.correlate(
    IDENT_KVNR_TOOL_ID,
    versions = setOf(1),
    hint = Text("Konto der eigenen Person im Personenverzeichnis zuordnen"),
    claims = setOf(AttributeType.PERSON_ID, AttributeType.KVNR, AttributeType.MEMBER_NUMBER),
    vouchedBy = ClaimSource.PERSON_DIRECTORY,
    requires = setOf(
        ClaimRequirement(AttributeType.FAMILY_NAME, ClaimTrust.PROVEN),
        ClaimRequirement(AttributeType.GIVEN_NAMES, ClaimTrust.PROVEN),
        ClaimRequirement(AttributeType.BIRTH_DATE, ClaimTrust.PROVEN),
    ),
)

/**
 * A method module talks to the orchestrator through tool_api only (docs/03-tool-architektur.md #2,
 * docs/04-orchestrierung.md #5). It does not depend on `ident_eid`: the link is the account's claims
 * plus this tool's `requires`, so any future attestation procedure feeds it unchanged (ADR-18).
 */
@ApplicationModule(id = "ident_kvnr", allowedDependencies = ["tool_api", "texts"])
@Configuration
internal class KvnrToolModule {
    @Bean
    fun kvnrModule() = KvnrModule
}
