package com.example.identity.core.account

import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.directory.normalizeKvnr

/**
 * Typed lookups over the normalized anchor index, the only place these values are stored. They
 * find an account still being set up too ([AccountService.anchorHolder]); a login goes through
 * `AccountDirectory.resolveByAnchor` instead (ADR-46).
 */
fun AccountService.findAccountByEmail(email: String): AccountProfile? =
    anchorHolder(AttributeType.EMAIL, email)?.let { findAccount(it) }

fun AccountService.findAccountByPersonId(personId: String): AccountProfile? =
    anchorHolder(AttributeType.PERSON_ID, personId)?.let { findAccount(it) }

/** KVNR ownership is current master data, not a historical claim or a local account anchor. */
fun AccountService.findAccountByKvnr(kvnr: String, personDirectory: PersonDirectory): AccountProfile? =
    personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnr))?.let { findAccountByPersonId(it) }
