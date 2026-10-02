package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber

/**
 * Whom a failed lookup attempt was against, which decides the brute-force counter it is charged
 * to. An account for a password, TAN or code of that account; a person for a one-time password
 * (`auth-invite-lookup`), which belongs to a person the way a Freischaltcode does.
 */
sealed interface Attempted {
    data class Account(val id: AccountId) : Attempted
    data class Person(val id: PartnerNumber) : Attempted
}
