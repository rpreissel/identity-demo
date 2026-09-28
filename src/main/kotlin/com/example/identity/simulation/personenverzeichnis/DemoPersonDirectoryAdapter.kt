package com.example.identity.simulation.personenverzeichnis

import com.example.identity.contract.tool_api.directory.DemoPersonDirectory
import com.example.identity.contract.tool_api.directory.DemoPersonRecord
import org.springframework.stereotype.Component

/** The register's side of [DemoPersonDirectory] - demo disclosure only, see there. */
@Component
class DemoPersonDirectoryAdapter(
    private val register: Personenverzeichnis,
    private val freischaltcodes: Freischaltcodes,
) : DemoPersonDirectory {

    override fun allPersons(): List<DemoPersonRecord> =
        with(register) {
            allePersonen().mapNotNull { data -> data.toPersonRecord()?.let { DemoPersonRecord(it, data.email, data.mobilnummer) } }
        }

    override fun latestValidActivationCode(personId: String): String? =
        freischaltcodes.juengsterGueltigerCode(personId)
}
