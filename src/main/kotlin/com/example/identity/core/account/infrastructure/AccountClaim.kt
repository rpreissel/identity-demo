package com.example.identity.core.account.infrastructure

import com.example.identity.core.account.domain.normalizeClaimValue
import com.example.identity.contract.tool_api.claims.AttributeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/**
 * One claim about an account's identity, with its source and the LOA it was established at. Only
 * appended, never overwritten. The current value of an anchor attribute lives in [AccountAnchor].
 */
@Entity
@Table(schema = "account", name = "claim")
class AccountClaim(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "attribute_type", nullable = false)
    var attributeType: AttributeType? = null,

    @Column(name = "claim_value", nullable = false)
    var value: String? = null,

    @Column(name = "normalized_value", nullable = false)
    var normalizedValue: String? = null,

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
    var authMethodId: java.util.UUID? = null,

    @Column(name = "established_at", nullable = false)
    var establishedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @PrePersist
    @PreUpdate
    fun normalizeValue() {
        normalizedValue = normalizeClaimValue(attributeType, value)
    }
}
