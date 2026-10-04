package com.example.identity.core.account

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import io.micrometer.core.instrument.MeterRegistry
import com.example.identity.core.account.infrastructure.AccountRepository
import com.example.identity.core.account.infrastructure.SignInType
import com.example.identity.core.account.infrastructure.SignInLogEntry
import com.example.identity.core.account.infrastructure.SignInLogRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** One line of the sign-in log, as [SignInLog.of] hands it out. */
data class SignInRecord(
    val signInType: String,
    val channel: String?,
    val acr: String?,
    val details: Map<String, Any?>,
    val occurredAt: Instant,
)

/**
 * The account's sign-in log (ADR-39, addendum). One function per event fixes the keys each event
 * carries. Joins the caller's transaction, so a sign-in is logged exactly when it happens. Deleted
 * with the account: login history is behaviour, not proof. Public, because sign-ins happen in the
 * orchestrator.
 */
@Service
class SignInLog(
    private val repository: SignInLogRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
) {

    /**
     * An entry journey (logging in, registering, a peer login) left the channel authenticated.
     * [tools]: the orchestrator tools behind [amr], each in the version the client spoke
     * (`auth-sms@1`, ADR-51); proofs Keycloak made itself have none.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    fun signedIn(accountId: AccountId, channel: String?, acr: String?, amr: List<String>, tools: List<String>, intent: String) =
        record(accountId, SignInType.SIGNED_IN, channel, acr, mapOf("amr" to amr, "tools" to tools, "intent" to intent))

    /** A STEP_UP journey raised the level of an authenticated channel. [tools] as in [signedIn]. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun steppedUp(accountId: AccountId, channel: String?, acr: String?, amr: List<String>, tools: List<String>) =
        record(accountId, SignInType.STEPPED_UP, channel, acr, mapOf("amr" to amr, "tools" to tools))

    /**
     * One proof of [accountId] failed with [method] - the account was known, the proof was wrong.
     * [tool] is the tool in the version the client spoke, `null` for a check Keycloak asked for.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    fun signInFailed(accountId: AccountId, channel: String?, method: String, tool: String?) =
        record(accountId, SignInType.SIGN_IN_FAILED, channel, details = mapOf("method" to method, "tool" to tool))

    /** That failure locked the account until [lockedUntil]. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun lockedOut(accountId: AccountId, channel: String?, lockedUntil: Instant) =
        record(accountId, SignInType.LOCKED_OUT, channel, details = mapOf("lockedUntil" to lockedUntil.toString()))

    /** A session ended on purpose. [endedBy]: `HOLDER` or `IDENTITY_PROVIDER` (Keycloak ended it). */
    @Transactional(propagation = Propagation.REQUIRED)
    fun signedOut(accountId: AccountId, channel: String?, endedBy: String) =
        record(accountId, SignInType.SIGNED_OUT, channel, details = mapOf("endedBy" to endedBy))

    /** A process access (ADR-48) left a Web channel signed in as [invitation]. [tools] as in [signedIn]. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun invitationSignedIn(invitation: InvitationId, channel: String?, acr: String?, amr: List<String>, tools: List<String>) =
        save(SignInLogEntry(invitation = invitation, signInType = SignInType.SIGNED_IN, channel = channel, acr = acr,
            details = details(SignInType.SIGNED_IN, mapOf("amr" to amr, "tools" to tools)), occurredAt = clock.instant()))

    /** A session of [invitation] ended on purpose; [endedBy] as in [signedOut]. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun invitationSignedOut(invitation: InvitationId, channel: String?, endedBy: String) =
        save(SignInLogEntry(invitation = invitation, signInType = SignInType.SIGNED_OUT, channel = channel,
            details = details(SignInType.SIGNED_OUT, mapOf("endedBy" to endedBy)), occurredAt = clock.instant()))

    @Transactional(readOnly = true)
    fun of(accountId: AccountId): List<SignInRecord> =
        repository.findByAccountIdOrderByOccurredAt(accountId).map { it.toRecord() }

    @Transactional(readOnly = true)
    fun ofInvitation(invitation: InvitationId): List<SignInRecord> =
        repository.findByInvitationOrderByOccurredAt(invitation).map { it.toRecord() }

    private fun SignInLogEntry.toRecord() = SignInRecord(signInType.name, channel, acr, details.orEmpty(), occurredAt)

    private fun details(type: SignInType, details: Map<String, Any?>) =
        mapOf("type" to type.name, "version" to type.detailsVersion) + details.filterValues { it != null }

    private fun save(entry: SignInLogEntry) {
        repository.save(entry)
    }

    private fun record(
        accountId: AccountId, type: SignInType, channel: String?, acr: String? = null, details: Map<String, Any?> = emptyMap(),
    ) {
        // The log goes with the account - a session ending right after its account was deleted
        // (DELETE_ACCOUNT logs the channel out last) has nothing left to log against.
        if (!accounts.existsAccount(accountId)) return
        save(
            SignInLogEntry(
                accountId = accountId, signInType = type, channel = channel, acr = acr,
                details = details(type, details), occurredAt = clock.instant(),
            )
        )
    }
}

/**
 * Deletes sign-in log lines older than `account.sign-in-log.retention-months`, daily. In batches with
 * one transaction each: the table holds many millions of rows, and a single delete would hold its
 * locks and undo for all of them.
 */
@Component
class SignInLogRetention(
    private val repository: SignInLogRepository,
    private val transactions: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${account.sign-in-log.retention-months:6}") private val retentionMonths: Long,
    private val clock: Clock,
) {
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 300_000)
    fun sweep() {
        val deleted = purge(clock.instant())
        meterRegistry.counter("identity.retention.deleted", "table", "sign_in_log").increment(deleted.toDouble())
    }

    fun purge(now: Instant): Int {
        val cutoff = now.atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant()
        var total = 0
        while (true) {
            val deleted = transactions.execute {
                val ids = repository.idsOlderThan(cutoff, Pageable.ofSize(BATCH))
                if (ids.isEmpty()) 0 else repository.deleteByIdIn(ids)
            }
            if (deleted == 0) return total
            total += deleted
        }
    }

    private companion object {
        const val BATCH = 500
    }
}
