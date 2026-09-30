package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.core.account.AccountProfile
import com.example.identity.contract.tool_api.claims.ClaimRequirement

/**
 * Whether [account] already carries what a tool declares it needs: the attribute established at
 * no less than the required claim trust, counted over assertions minus retractions
 * (`AccountProfile.establishedClaims`, ADR-12).
 *
 * Generic on purpose: a tool can require an attested attribute without knowing which procedure
 * attested it. The offer and the check on direct activation both use this, so they cannot drift
 * apart.
 */
internal fun requiresSatisfied(requirement: ClaimRequirement, account: AccountProfile?): Boolean {
    val established = account?.establishedClaims?.get(requirement.attributeType) ?: return false
    return established.rank >= requirement.minClaimTrust.rank
}
