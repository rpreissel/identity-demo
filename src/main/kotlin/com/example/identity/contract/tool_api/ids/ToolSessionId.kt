package com.example.identity.contract.tool_api.ids

import java.util.UUID

/**
 * The id of a tool session, the innermost session level. Tool modules key their own session rows
 * by it. Entities and JSON keep the bare UUID.
 */
@JvmInline
value class ToolSessionId(val value: UUID) {
    override fun toString(): String = value.toString()
}
