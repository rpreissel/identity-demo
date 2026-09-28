package com.example.identity.simulation.personenverzeichnis.internal

import org.springframework.data.jpa.repository.JpaRepository

interface PersonRepository : JpaRepository<Person, String> {
    fun findByKvnr(kvnr: String): Person?
    fun findByVersnr(versnr: String): Person?
    fun findByGeburtsdatum(geburtsdatum: java.time.LocalDate): List<Person>
}
