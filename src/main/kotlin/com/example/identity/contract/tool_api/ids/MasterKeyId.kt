package com.example.identity.contract.tool_api.ids

import java.util.UUID

/**
 * The id of a master key in the account module (ADR-55): the key a journey seals under before
 * its account exists, and which the account adopts. Rows of method modules name it. Entities keep
 * the bare UUID.
 */
@JvmInline
value class MasterKeyId(val value: UUID) {
    override fun toString(): String = value.toString()
}
