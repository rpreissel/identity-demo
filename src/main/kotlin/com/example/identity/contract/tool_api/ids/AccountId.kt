package com.example.identity.contract.tool_api.ids

/**
 * The id of an account in the account module. Its own type, so it cannot be mixed up with another
 * `Long`. Entities and JSON keep the bare number.
 */
@JvmInline
value class AccountId(val value: Long) : Comparable<AccountId> {
    override fun compareTo(other: AccountId): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}
