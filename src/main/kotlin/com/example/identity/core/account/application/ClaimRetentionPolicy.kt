package com.example.identity.core.account.application

import com.example.identity.contract.tool_api.claims.AttributeType
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** `account.claims.retention.<wire name>: <ISO duration>`; an attribute without an entry is kept with the account. */
@ConfigurationProperties(prefix = "account.claims")
data class ClaimRetentionProperties(val retention: Map<String, Duration> = emptyMap())

/**
 * How long a claim's value stays readable (docs/07-betrieb.md Abschnitt 3). A batch holds only
 * attributes with one rule, so its data key can expire as a whole. Anchor attributes are refused:
 * their value also lives in `account.anchor`, which this policy does not touch, and an account
 * would stop recognizing its holder.
 */
@Component
class ClaimRetentionPolicy(properties: ClaimRetentionProperties) {
    private val retention: Map<AttributeType, Duration> = properties.retention.entries.associate { (wireName, duration) ->
        val type = checkNotNull(AttributeType.fromWireName(wireName)) { "account.claims.retention: unknown attribute '$wireName'" }
        check(!type.isLocalAnchor) { "account.claims.retention: '$wireName' is an anchor attribute and cannot expire" }
        check(!duration.isNegative && !duration.isZero) { "account.claims.retention.$wireName must be a positive duration" }
        type to duration
    }

    /** `null` means no expiry. */
    fun retentionOf(type: AttributeType): Duration? = retention[type]
}
