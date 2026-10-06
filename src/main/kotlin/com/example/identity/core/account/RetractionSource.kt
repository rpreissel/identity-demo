package com.example.identity.core.account

/**
 * Who withdrew a claim, on whose authority: a retraction carries provenance like a claim (ADR-12).
 * Not a [com.example.identity.contract.tool_api.claims.ClaimSource], because a tool can never retract, so the two
 * vocabularies must not be interchangeable.
 */
enum class RetractionSource {
    /** The account lifecycle itself, e.g. revoking the method instance that established the claim. */
    ACCOUNT_MANAGEMENT,

    /** The account holder in self-service, where [com.example.identity.contract.tool_api.claims.AnchorRule.retractableByHolder]. */
    ACCOUNT_HOLDER,

    /** The master-data backend no longer carries the value (e.g. a KVNR that was deregistered). */
    PERSON_DIRECTORY,

    /** A human operator, with a reason - the escape hatch for everything the two above do not cover. */
    OPERATOR,

    /** The retention rule of the attribute ran out (`account.claims.retention`, ADR-52); nobody acted. */
    RETENTION_POLICY
}
