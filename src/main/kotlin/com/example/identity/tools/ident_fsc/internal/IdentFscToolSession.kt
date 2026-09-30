package com.example.identity.tools.ident_fsc.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Tool-session-scoped working data for toolId=ident-fsc (docs/06-ablaeufe.md #1). */
@Entity
@Table(schema = "ident_fsc", name = "ident_tool_session")
class IdentFscToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    var kvnr: String? = null,

    var partnerNumber: String? = null,

    @Column(name = "person_id")
    var personId: String? = null,

    var familyName: String? = null,
    var givenNames: String? = null,
    var birthDate: LocalDate? = null,
    /** SHA-256 of the submitted code - the code itself is never persisted. */
    @Column(name = "fsc_hash")
    var fscHash: String? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
