package com.example.identity.core.account.application

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.infrastructure.strongestEstablishedValues
import com.example.identity.core.account.domain.passportForm
import com.example.identity.core.account.infrastructure.AccountAnchorRepository
import com.example.identity.core.account.infrastructure.AccountClaimRepository
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.directory.ClaimedIdentity
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.directory.IdentityResolver
import com.example.identity.contract.tool_api.directory.MatchedVia
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.directory.Resolution
import com.example.identity.contract.tool_api.directory.normalizeKvnr
import com.example.identity.contract.tool_api.claims.ClaimTrust
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Answers "does an existing account belong to these claims?" with one matching policy for every
 * identification procedure (docs/02-domaenenmodell.md #6). Resolution runs on anchors only (ADR-19).
 * Attested identity data is checked against the register, never matched against the account stock:
 * attribute combinations are ambiguous, and a false merge is the most expensive error.
 */
@Service
class IdentityMatchingService(
    private val accountAnchorRepository: AccountAnchorRepository,
    private val accountClaimRepository: AccountClaimRepository,
    private val personDirectory: PersonDirectory
) : IdentityResolver {

    companion object {
        /** What an attestation can establish and the register can be checked against. */
        private val ATTESTABLE_IDENTITY_ATTRIBUTES =
            setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)

        /**
         * Tells apart register persons with the same name and date of birth (ADR-18, addendum). An
         * attestation without an address, or with a moved one, goes on to the Freischaltcode letter.
         */
        private val ADDRESS_ATTRIBUTES = setOf(AttributeType.STREET_ADDRESS, AttributeType.POSTAL_CODE, AttributeType.LOCALITY)
    }

    override fun resolve(claims: Set<Claim>): Resolution {
        val kvnrClaim = claims.firstOrNull { it.attributeType == AttributeType.KVNR }
        val personClaim = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }
        // A KVNR here always comes from the Personenverzeichnis itself (ToolHandlerRegistry refuses
        // any other source at startup) - it was checked at the source, so it only resolves the person
        // when the claims carry no person reference of their own.
        val externalPersonId = if (kvnrClaim != null && personClaim == null) {
            personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnrClaim.value))
        } else {
            null
        }
        return resolveByAnchor(claims, externalPersonId?.value) ?: Resolution.Unresolved
    }

    /**
     * Checks a person reference against an attestation from an earlier step that lives on the
     * account (`ident-kvnr`). Per attribute the strongest surviving claim wins, recency only breaks
     * ties (docs/02-domaenenmodell.md #6). `ident-fsc` checks its own input against the register.
     */
    override fun attestedIdentityMatches(accountId: AccountId, personId: PartnerNumber): Boolean {
        val attested = accountClaimRepository.findEstablished(accountId)
            .strongestEstablishedValues(ATTESTABLE_IDENTITY_ATTRIBUTES + ADDRESS_ATTRIBUTES)
        // All of them, not "whatever was attested": ClaimedIdentity skips a null field by design, so
        // a missing date of birth would quietly fall back to the name alone.
        if (!attested.keys.containsAll(ATTESTABLE_IDENTITY_ATTRIBUTES)) return false
        val withAddress = personDirectory.hasNamesake(personId)
        if (withAddress && !attested.keys.containsAll(ADDRESS_ATTRIBUTES)) return false
        return personDirectory.matchesMasterData(
            personId,
            ClaimedIdentity(
                familyName = attested.getValue(AttributeType.FAMILY_NAME),
                givenNames = attested.getValue(AttributeType.GIVEN_NAMES),
                birthDate = LocalDate.parse(attested.getValue(AttributeType.BIRTH_DATE)),
                streetAddress = attested[AttributeType.STREET_ADDRESS].takeIf { withAddress },
                postalCode = attested[AttributeType.POSTAL_CODE].takeIf { withAddress },
                locality = attested[AttributeType.LOCALITY].takeIf { withAddress }
            )
        )
    }

    override fun attestationFits(accountId: AccountId, claims: Set<Claim>): Boolean {
        val attested = accountClaimRepository.findEstablished(accountId).strongestEstablishedValues(ATTESTABLE_IDENTITY_ATTRIBUTES)
        return ATTESTABLE_IDENTITY_ATTRIBUTES.all { type ->
            val before = attested[type] ?: return@all true
            val now = claims.firstOrNull { it.attributeType == type }?.value ?: return@all true
            passportForm(before) == passportForm(now)
        }
    }

    /**
     * Unique lookups via `account.anchor`. KVNR resolves live to the external person ID and then to
     * that person's anchor. Claims are ranked by [AnchorRule.bindingStrength], not by [ClaimTrust] or
     * set order, so the strongest anchor (PERSON_ID) is always consulted first.
     */
    private fun resolveByAnchor(claims: Set<Claim>, externalPersonId: String?): Resolution.ExistingAccount? {
        val matches = mutableListOf<Resolution.ExistingAccount>()
        externalPersonId?.let { personId ->
            accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, personId)
                ?.accountId?.let { matches.add(Resolution.ExistingAccount(it, MatchedVia.Anchor(AttributeType.PERSON_ID))) }
        }
        for (claim in claims
            .filter { it.attributeType.isLocalAnchor }
            .sortedByDescending { checkNotNull(it.attributeType.anchorRule) { "${it.attributeType} has no anchor rule" }.bindingStrength }
        ) {
            val anchor = accountAnchorRepository.findByAttributeTypeAndValue(
                claim.attributeType,
                claim.attributeType.normalizeAnchorValue(claim.value)
            ) ?: continue
            val accountId = anchor.accountId ?: continue
            matches.add(Resolution.ExistingAccount(accountId, MatchedVia.Anchor(claim.attributeType)))
        }
        if (matches.map { it.accountId }.distinct().size > 1) {
            throw IdentityConflictException(Text("Identitaetsanker verweisen auf unterschiedliche Konten"))
        }
        return matches.maxByOrNull { it.matchedVia.bindingStrength }
    }
}
