package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

/** What happened to a sign-in (ADR-39, addendum). [detailsVersion] works like [ChangeType.detailsVersion]. */
enum class SignInType(val detailsVersion: Int) {
    /** An entry journey (logging in, registering, a peer login) left the channel authenticated. */
    SIGNED_IN(2),

    /** A STEP_UP journey raised an authenticated channel's level. */
    STEPPED_UP(2),

    /** One proof of an existing account failed - a wrong password, TAN, OTP. */
    SIGN_IN_FAILED(2),

    /** That failure tripped the account's brute-force lock. */
    LOCKED_OUT(1),

    /** A session of the account ended on purpose - never a mere expiry. */
    SIGNED_OUT(1),
}

/**
 * One line of the sign-in log. Unlike [ChangeLogEntry] it is deleted with the account and lives
 * only months. It belongs to an account or to an invitation (ADR-48), never both
 * (`ck_sign_in_log_one_subject`).
 */
@Entity
@Table(schema = "account", name = "sign_in_log")
class SignInLogEntry(
    @Column(name = "account_id", updatable = false)
    val accountId: AccountId? = null,

    @Column(name = "invitation", updatable = false, length = 64)
    val invitation: InvitationId? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "sign_in_type", nullable = false, updatable = false, length = 32)
    val signInType: SignInType = SignInType.SIGNED_IN,

    /** The channel it happened on (`APP`, `WEB`). */
    @Column(name = "channel", updatable = false, length = 16)
    val channel: String? = null,

    @Column(name = "acr", updatable = false, length = 16)
    val acr: String? = null,

    /** The event's keys as JSON, sealed under the account's key; for an invitation, readable in every mode (ADR-55). */
    @Column(name = "details", updatable = false)
    val sealedDetails: ByteArray? = null,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null
}

interface SignInLogRepository : JpaRepository<SignInLogEntry, Long> {
    fun findByAccountIdOrderByOccurredAt(accountId: AccountId?): List<SignInLogEntry>

    fun findByInvitationOrderByOccurredAt(invitation: InvitationId): List<SignInLogEntry>

    /** One retention batch, oldest first (`SignInLogRetention`). */
    @Query("select e.id from SignInLogEntry e where e.occurredAt < :cutoff order by e.occurredAt")
    fun idsOlderThan(cutoff: Instant, pageable: Pageable): List<Long>

    @Modifying
    @Query("delete from SignInLogEntry e where e.id in :ids")
    fun deleteByIdIn(ids: Collection<Long>): Int
}
