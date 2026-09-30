package com.example.identity.contract.tool_api.directory

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.claims.AttributeType


/**
 * The Personenverzeichnis changed a person (ADR-34): who, which kinds of attributes, and the new
 * identifiers (`null` = none now). Only [kvnr] and [memberNumber] carry values, because the
 * account stores them; everything else is read live. In `tool_api`, so publisher and listener do
 * not know each other.
 */
data class PersonChanged(
    val personId: PartnerNumber,
    val changed: Set<AttributeType>,
    val kvnr: String?,
    val memberNumber: String?
)
