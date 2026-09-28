package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.directory.DemoPersonDirectory
import org.springframework.stereotype.Component

/**
 * One demo persona as the frontend's picker receives it. The register supplies everything it
 * knows, live. Only the eID card's restricted identifier is demo configuration, so a person
 * created on `/personenverzeichnis/` shows up with that one empty.
 */
data class DemoPerson(
    /** The Partnernummer; every person has one (ADR-34). */
    val personId: String,
    /** Only for a person insured with us, and even then possibly missing for a while. */
    val kvnr: String?,
    val familyName: String?,
    val givenNames: String?,
    val email: String?,
    /** The mobile number as the register keeps it. */
    val phoneNumber: String?,
    /** Street and house number in one line, as the eID card shows it. */
    val streetAddress: String?,
    val postalCode: String?,
    val locality: String?,
    val birthDate: String?,
    /** Plaintext of the newest valid letter in the register's mailbox (ADR-31). */
    val fscCode: String?,
    val restrictedId: String?
)

/**
 * The eID card's restricted identifier, keyed by Partnernummer for the persons
 * `demo_seed/V16__testdata.sql` seeds. A register knows no card, so it stays here.
 */
private val DEMO_RESTRICTED_IDS = mapOf(
    "P000000001" to "T0103005K1D5S0V8T9W6UM2RTX",
    "P000000002" to "T0208011X7Y2Q4M6B3LT0T28WJ",
    "P000000003" to "T0304223A9B1N7K5D2PN1S44QE",
    "P000000004" to "T0405337C2D8R5H9F1QW3V61MZ",
)

/** Reads the personas off the register over its demo port. Only [DisclosingDemoDisclosure] calls this. */
@Component
class DemoPersonas(private val register: DemoPersonDirectory) {
    fun all(): List<DemoPerson> = register.allPersons().map { entry ->
        val person = entry.person
        DemoPerson(
            personId = person.personId,
            kvnr = person.kvnr,
            familyName = person.familyName,
            givenNames = person.givenNames,
            email = entry.email,
            phoneNumber = entry.phoneNumber,
            streetAddress = person.streetAddress,
            postalCode = person.postalCode,
            locality = person.locality,
            birthDate = person.birthDate?.toString(),
            fscCode = register.latestValidActivationCode(person.personId),
            restrictedId = DEMO_RESTRICTED_IDS[person.personId]
        )
    }
}
