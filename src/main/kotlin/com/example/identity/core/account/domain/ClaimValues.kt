package com.example.identity.core.account.domain

import com.example.identity.contract.tool_api.claims.AttributeType
import java.util.UUID

/**
 * The claim log's normalization rule: trimmed and lowercased, except card pseudonyms, where case
 * matters and the anchor keeps it too. Writing and subtracting retractions both depend on this
 * rule, or a withdrawn value silently keeps counting (ADR-12).
 */
fun normalizeClaimValue(type: AttributeType?, value: String?): String? =
    value?.trim()?.let { if (type in CASE_PRESERVING) it else it.lowercase() }

private val CASE_PRESERVING = setOf(AttributeType.EID_RESTRICTED_ID, AttributeType.NECT_RESTRICTED_ID)

/**
 * What makes a claim already logged. The claim log is a change log, not a run log. The method
 * instance is part of the key, so a fresh enrollment of a known value still logs: revoking the old
 * instance retracts only what that instance asserted.
 */
data class ClaimKey(val type: AttributeType, val normalizedValue: String, val source: String, val authMethodId: UUID?)
