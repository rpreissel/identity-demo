package com.example.identity.core.account

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.claims.ClaimTrust
import java.time.Instant

data class AuthMethodView(
    val id: String,
    val method: String,
    val active: Boolean,
    val createdAt: Instant?,
    val enrolledUnderAcr: String?,
    /** The caller key this instance lives on, for a one-per-device method; `null` otherwise. */
    val boundKeyRef: String?,
    /** What of this instance may be shown on the device it lives on (e.g. the KOBIL phone id). */
    val reference: String?,
    val enrollmentRef: EnrollmentRef,
    val label: String? = null
)

data class AccountProfile(
    val accountId: AccountId,
    /** Null for an account that was never identified, which may be a permanent state. */
    val personId: PartnerNumber?,
    val authenticationMethods: List<AuthMethodView>,
    /** The account's EMAIL anchor, i.e. its normalized form. */
    val email: String? = null,
    val emailConfirmedAt: Instant? = null,
    /**
     * Each established attribute at the highest [ClaimTrust] a non-retracted claim carries (ADR-12).
     * `Tool.requires` is checked against this.
     */
    val establishedClaims: Map<AttributeType, ClaimTrust> = emptyMap()
) {
    /**
     * Set up: at least one login method was ever enrolled (ADR-46), so the account can be logged
     * into. Until then it is being set up: not findable for a login, recognized by no device, and
     * discarded as a whole when its registration is abandoned. Deactivated instances count, so a
     * revoked device does not turn an account back. Independent of the role (prospect).
     */
    val isSetUp: Boolean
        get() = authenticationMethods.isNotEmpty()

    /** Every active entry, including several instances of one method (e.g. several devices). */
    val activeAuthenticationMethods: List<AuthMethodView>
        get() = authenticationMethods.filter { it.active }

    /**
     * Anchor-derived shorthand, kept because Keycloak's own `emailVerified` mirrors it. Agrees
     * with `establishedClaims[EMAIL]` by construction: a retraction deletes the anchor row too.
     */
    val emailConfirmed: Boolean
        get() = emailConfirmedAt != null

    /**
     * No person binding yet (ADR-10), so the account may still adopt a freshly attested identity.
     * An identified account may not: binding someone else's eID claims onto it would merge two people.
     */
    val isUnidentified: Boolean
        get() = personId == null

    /**
     * [isUnidentified] and no credential was ever enrolled: a placeholder the journey created for
     * itself. The one rule behind every operation that may treat an account as disposable (ADR-20).
     * Deactivated instances count, because a revoked instance still owns claim provenance (ADR-12).
     */
    val isDisposable: Boolean
        get() = isUnidentified && !isSetUp
}
