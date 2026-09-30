package com.example.identity.tools.ident_eid.internal

import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/** Tool-session-scoped working data for toolId=ident-eid. */
@Entity
@Table(schema = "ident_eid", name = "ident_tool_session")
class IdentEidToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,

    /** The simulated eID card's Ausweisdaten, the first stage of the "input" step. */
    var familyName: String? = null,
    var givenNames: String? = null,
    var birthDate: LocalDate? = null,
    var streetAddress: String? = null,
    var postalCode: String? = null,
    var locality: String? = null,

    /** The card's restricted identifier - person-unique pseudonym, ADR-19's recognition anchor. */
    @Column(name = "restricted_id", length = 64)
    var restrictedId: String? = null,

    /** SHA-256 of the submitted PIN - the PIN itself is never persisted. */
    @Column(name = "pin_hash")
    var pinHash: String? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
