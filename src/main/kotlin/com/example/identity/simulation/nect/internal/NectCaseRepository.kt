package com.example.identity.simulation.nect.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NectCaseRepository : JpaRepository<NectCase, UUID>
