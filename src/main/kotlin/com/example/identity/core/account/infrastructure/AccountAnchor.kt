package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.claims.AttributeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The current normalized value an account holds for a locally owned attribute, at most one per
 * (account, attribute type). `UNIQUE(attribute_type, normalized_value)` makes resolving an identity a
 * lookup instead of a match. First writer wins; a cross-account conflict is rejected, never
 * re-assigned (ADR-11). Provenance stays in [AccountClaim].
 */
@Entity
@Table(schema = "account", name = "anchor")
class AccountAnchor(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    // Same converter as account.claim.attribute_type: both columns hold AttributeType.wireName.
    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "attribute_type", nullable = false)
    var attributeType: AttributeType? = null,

    @Column(name = "normalized_value", nullable = false)
    var value: String? = null,

    /**
     * What was proven when this value was bound, capped like `AccountAuthMethod.enrolledUnderAcr`
     * (ADR-5). `null` for older rows.
     */
    @Column(name = "established_acr", length = 16)
    var establishedAcr: String? = null,

    @Column(name = "established_at", nullable = false)
    var establishedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
