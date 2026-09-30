package com.example.identity.simulation.personenverzeichnis.internal

import com.example.identity.contract.tool_api.values.PartnerNumber
import org.springframework.data.jpa.repository.JpaRepository

interface FreischaltcodeRepository : JpaRepository<Freischaltcode, Long> {
    fun findByPersonIdAndCodeHash(personId: PartnerNumber, codeHash: String): List<Freischaltcode>
    fun findByPersonIdOrderByIdDesc(personId: PartnerNumber): List<Freischaltcode>
}

interface BriefRepository : JpaRepository<Brief, Long> {
    fun findByPersonIdOrderByIdDesc(personId: PartnerNumber): List<Brief>
    fun findAllByOrderByIdDesc(): List<Brief>
    fun findFirstByEinladungIdOrderByIdDesc(einladungId: String): Brief?
}
