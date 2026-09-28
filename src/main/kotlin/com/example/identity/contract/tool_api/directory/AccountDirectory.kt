package com.example.identity.contract.tool_api.directory

import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.EnrollmentRef


/**
 * Read-only account and credential lookups for a tool controller. Results are opaque ids and
 * enrollment references only, never account profile data.
 */
interface AccountDirectory {
    /**
     * Resolves the account that has established [value] as its anchor of [type], compared in
     * normalized form. [type] must be a local anchor attribute. KVNR is none: it resolves through
     * [PersonDirectory] to a person id and then through the PERSON_ID anchor.
     *
     * @return the account id, or `null` if no account has established this anchor.
     */
    fun resolveByAnchor(type: AttributeType, value: String): Long?

    /**
     * The account's value for anchor [type] in normalized form; the reverse of [resolveByAnchor].
     * `null` if never established. A rejected change keeps the previous value. Non-anchor types
     * are contract errors.
     */
    fun anchorValue(accountId: Long, type: AttributeType): String?

    /**
     * The account's currently active credential for [method] (e.g. `"sms"`, `"password"`).
     *
     * @return the enrollment reference, or `null` if the account has no active credential for
     * this method.
     */
    fun activeEnrollment(accountId: Long, method: String): EnrollmentRef?

    /**
     * The account's active credential for [method] whose instance details satisfy
     * [livesOnCallerKey]. For multi-instance methods (e.g. `"device"`), where only one instance
     * belongs to the calling device. The caller supplies the predicate (typically
     * `ToolDescriptor.keyBinding`), because `account` stores instance details as an opaque blob.
     *
     * @return the first active instance [livesOnCallerKey] accepts, or `null`.
     */
    fun activeInstanceEnrollment(accountId: Long, method: String, livesOnCallerKey: (instanceDetails: Map<String, Any?>?) -> Boolean): EnrollmentRef?
}

/**
 * The credential of the `email` method is not a module-owned row but the account's own EMAIL
 * anchor (`account.anchor`, keyed by account and attribute type) - every enrollment of that
 * method references it the same way, and no `EnrollmentCleanup` exists for it: the anchor goes
 * with the account.
 */
val EMAIL_ANCHOR_ENROLLMENT = EnrollmentRef(type = "account.anchor", id = "email")
