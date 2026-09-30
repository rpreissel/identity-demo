package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.claimTrust
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/** Append-only identity log. Readers use [findEstablished] and compare [AccountClaim.normalizedValue]. */
@Repository
interface AccountClaimRepository : JpaRepository<AccountClaim, Long> {

    /** Everything one method instance ever asserted - the retraction path's only query. */
    fun findByAuthMethodId(authMethodId: UUID): List<AccountClaim>

    /**
     * What this account currently asserts: assertions minus retractions (ADR-12). A retraction only
     * cancels assertions made before it, so a value re-proven after its withdrawal counts again;
     * otherwise an e-mail cycle A -> B -> A could never return to A. Ranking stays in Kotlin, because
     * the source is stored untyped (db/migration/KONVENTIONEN.md).
     */
    @Query(
        """
        select a from AccountClaim a
        where a.accountId = :accountId
          and not exists (
              select r.id from AccountRetraction r
              where r.accountId = a.accountId
                and r.attributeType = a.attributeType
                and r.normalizedValue = a.normalizedValue
                and r.retractedAt >= a.establishedAt
          )
        """
    )
    fun findEstablished(@Param("accountId") accountId: AccountId?): List<AccountClaim>
}

/**
 * The strongest surviving value per attribute: trust rank first, recency only breaks ties. Shared
 * by every established-claims reader, so they all select identically.
 */
internal fun List<AccountClaim>.strongestEstablishedValues(types: Set<AttributeType>): Map<AttributeType, String> =
    filter { it.attributeType in types && it.value != null }
        .groupBy { it.attributeType!! }
        .mapValues { (_, claims) ->
            claims.maxWith(
                compareBy<AccountClaim>({ ClaimSource(it.claimSource.orEmpty()).claimTrust.rank }, { it.establishedAt })
            ).value!!
        }
