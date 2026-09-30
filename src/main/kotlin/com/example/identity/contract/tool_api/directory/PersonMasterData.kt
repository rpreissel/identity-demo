package com.example.identity.contract.tool_api.directory

import com.example.identity.contract.tool_api.values.PartnerNumber
import java.time.LocalDate

/**
 * A person's master data, for one consumer: the identity provider (Keycloak), which puts name,
 * address and identifiers into the tokens. A port of its own, because [PersonDirectory] never
 * lets master data cross; this keeps visible who reads it and why.
 */
interface PersonMasterData {
    /** The record of [personId], or `null` if the directory does not know it. */
    fun masterDataOf(personId: PartnerNumber): PersonRecord?
}

/** What the directory holds about a person, as the account mirror needs it. */
data class PersonRecord(
    /** The Partnernummer - the person id (ADR-34). */
    val personId: PartnerNumber,
    val kvnr: String?,
    val familyName: String?,
    val givenNames: String?,
    val birthDate: LocalDate?,
    /** Street and house number as one line - the form documents attest it (`AttributeType.STREET_ADDRESS`). */
    val streetAddress: String?,
    val postalCode: String?,
    val locality: String?,
    /** The Versicherungsnummer, only for a person insured with us. */
    val memberNumber: String?
)
