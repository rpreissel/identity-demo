package com.example.identity.contract.tool_api.directory


/**
 * Demo disclosure only: what the persona picker pre-fills the forms with. A port of its own, so
 * no production path reaches it by accident; listing every person is fatal for a real register.
 */
interface DemoPersonDirectory {
    /** Every person the register knows. */
    fun allPersons(): List<DemoPersonRecord>

    /**
     * The plaintext of the newest still-valid code in [personId]'s letters, or `null` - the one
     * place a Freischaltcode leaves the register in plain text, and only because the demo mailbox
     * shows it anyway.
     */
    fun latestValidActivationCode(personId: String): String?
}

/**
 * One register person for the picker: the master data plus the contact details only the demo reads.
 * They stay out of [PersonRecord], because no identity or claim depends on them.
 */
data class DemoPersonRecord(
    val person: PersonRecord,
    val email: String?,
    val phoneNumber: String?,
)
