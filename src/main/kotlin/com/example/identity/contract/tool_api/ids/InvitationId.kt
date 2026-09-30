package com.example.identity.contract.tool_api.ids

/**
 * The id of an invitation of the person register: SHA-256 over person, one-time password and
 * process (ADR-48). Entities and JSON keep the bare string.
 */
@JvmInline
value class InvitationId(val value: String) {
    override fun toString(): String = value
}
