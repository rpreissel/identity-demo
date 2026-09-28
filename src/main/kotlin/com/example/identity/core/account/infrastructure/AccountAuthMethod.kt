package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.EnrollmentRef
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * One registered authentication method instance (docs/06-ablaeufe.md #1). The credential belongs to
 * the method module; this row holds the only [EnrollmentRef] to it. Deactivated rows stay, so
 * account deletion still reaches every credential. [enrolledUnderAcr] caps what the method can
 * authenticate to (ADR-5). [details] is opaque data only the owning module reads, never audit evidence.
 */
@Entity
@Table(schema = "account", name = "auth_method")
class AccountAuthMethod(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "method", nullable = false)
    var method: String? = null,

    @Column(name = "enrollment_type", nullable = false)
    var enrollmentType: String? = null,

    @Column(name = "enrollment_id", nullable = false)
    var enrollmentId: String? = null,

    @Column(name = "enrolled_under_acr")
    var enrolledUnderAcr: String? = null,

    @Column(name = "label")
    var label: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    var details: Map<String, Any?>? = null
) {
    /** What `DELETE .../methods/{id}` addresses, since a method name may have several active instances. */
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = UUID.randomUUID()

    @Column(name = "active", nullable = false)
    var active: Boolean = true

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = Instant.now()

    @Column(name = "deactivated_at")
    var deactivatedAt: Instant? = null

    val enrollmentRef: EnrollmentRef
        get() = EnrollmentRef(checkNotNull(enrollmentType), checkNotNull(enrollmentId))

    fun deactivate(at: Instant) {
        active = false
        deactivatedAt = at
    }
}
