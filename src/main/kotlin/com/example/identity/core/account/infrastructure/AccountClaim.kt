package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
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
 * One claim about an account's identity, with its source and the LOA it was established at. Only
 * appended, never overwritten. The current value of an anchor attribute lives in [AccountAnchor].
 * The value is stored encrypted under the data key of its [claimBatchId] (ADR-52); equality within
 * the account goes by [valueDigest]. `ClaimCrypto` writes and reads both.
 */
@Entity
@Table(schema = "account", name = "claim")
class AccountClaim(
    @Column(name = "account_id", nullable = false)
    var accountId: AccountId? = null,

    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "attribute_type", nullable = false)
    var attributeType: AttributeType? = null,

    /** Nonce, ciphertext and tag of the value, AES-256-GCM under the batch's data key. */
    @Column(name = "claim_value", nullable = false)
    var encryptedValue: ByteArray? = null,

    /** HMAC of the normalized value under the account's key - what dedup and retraction compare. */
    @Column(name = "value_digest", nullable = false)
    var valueDigest: String? = null,

    /** The group of claims recorded in one call, sharing one data key and one retention rule. */
    @Column(name = "claim_batch_id", nullable = false)
    var claimBatchId: UUID? = null,

    // Raw String, not the ClaimSource value class: Hibernate hands an AttributeConverter the unboxed
    // String for a Kotlin value-class property and fails at runtime.
    @Column(name = "claim_source", nullable = false)
    var claimSource: String? = null,

    @Column(name = "established_acr")
    var establishedAcr: String? = null,

    /**
     * The method instance that established this claim, so revoking it retracts exactly what it
     * asserted (ADR-12). `null` for identification tools, which produce no credential.
     */
    @Column(name = "auth_method_id")
    var authMethodId: UUID? = null,

    @Column(name = "established_at", nullable = false)
    var establishedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
