package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.RetractionSource
import com.example.identity.contract.tool_api.claims.AttributeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One withdrawn (account, attribute type, value) triple. Cancels every matching [AccountClaim]
 * without touching it: the claim log stays append-only, and "currently valid" is the log minus
 * these rows, matched on [valueDigest]. No plaintext: the digest is all a retraction needs. With a
 * [claimBatchId] the retraction reaches only that batch (ADR-52, retention), otherwise every older row.
 */
@Entity
@Table(schema = "account", name = "retraction")
class AccountRetraction(
    @Column(name = "account_id", nullable = false)
    var accountId: AccountId? = null,

    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "attribute_type", nullable = false)
    var attributeType: AttributeType? = null,

    @Column(name = "value_digest", nullable = false)
    var valueDigest: String? = null,

    @Column(name = "claim_batch_id")
    var claimBatchId: UUID? = null,

    @Column(name = "claim_source", nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    var retractionSource: RetractionSource? = null,

    @Column(name = "reason")
    var reason: String? = null,

    @Column(name = "retracted_at", nullable = false)
    var retractedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
