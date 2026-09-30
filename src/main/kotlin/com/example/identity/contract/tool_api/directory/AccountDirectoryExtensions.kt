package com.example.identity.contract.tool_api.directory

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.claims.AttributeType


/** The account whose EMAIL anchor is [email], or `null` - the lookup tools' entry point. */
fun AccountDirectory.resolveAccountByEmail(email: String): AccountId? =
    resolveByAnchor(AttributeType.EMAIL, email)

/** The account bound to register person [personId], or `null` if nobody has claimed them yet. */
fun AccountDirectory.resolveAccountByPersonId(personId: PartnerNumber): AccountId? =
    resolveByAnchor(AttributeType.PERSON_ID, personId.value)

/** KVNR is resolved from current master data, never a locally stored KVNR anchor. */
fun AccountDirectory.resolveAccountByKvnr(kvnr: String, personDirectory: PersonDirectory): AccountId? =
    personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnr))?.let { resolveAccountByPersonId(it) }
