package com.example.identity.contract.tool_api.ids

import java.util.UUID

/** The id of a channel session, the outermost session level. Entities and JSON keep the bare UUID. */
@JvmInline
value class ChannelSessionId(val value: UUID) {
    override fun toString(): String = value.toString()
}
