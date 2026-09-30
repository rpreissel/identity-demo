package com.example.identity.core.account

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.directory.PersonChanged
import com.example.identity.contract.texts.Text
import com.example.identity.core.account.infrastructure.Account
import com.example.identity.core.account.infrastructure.accountId
import com.example.identity.core.account.application.AnchorRegistry
import com.example.identity.core.account.application.ClaimLedger
import com.example.identity.core.account.infrastructure.AccountAuthMethod
import com.example.identity.core.account.infrastructure.AccountAuthMethodRepository
import com.example.identity.core.account.infrastructure.AccountRepository
import com.example.identity.core.account.application.MethodDeactivationReason
import com.example.identity.core.account.application.ChangeLog
import com.example.identity.core.account.application.PersonLookupKey
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.claims.AttributeAuthority
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.claims.anchorRule
import com.example.identity.contract.tool_api.claims.authority
import com.example.identity.contract.tool_api.claims.isLocalAnchor
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.EnrollmentRef
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Fired once an account row is gone; the only account event, as Keycloak reads accounts itself (ADR-38). */
data class AccountDeleted(val accountId: AccountId)

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val claimLedger: ClaimLedger,
    private val anchorRegistry: AnchorRegistry,
    private val accountAuthMethodRepository: AccountAuthMethodRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val changeLog: ChangeLog,
    private val personLookupKey: PersonLookupKey,
    private val clock: Clock,
) : AccountDirectory {

    private val log = LoggerFactory.getLogger(AccountService::class.java)

    /** Single-claim convenience wrapper around [recordClaims]. */
    @Transactional
    fun recordClaim(accountId: AccountId, claim: Claim, provenAcr: AcrLevel) =
        recordClaims(accountId, listOf(claim), provenAcr)

    /**
     * Which attribute types this method instance asserted, i.e. what [retractClaimsOf] would retract.
     * Lets a caller check a removal against the channel's floor before writing anything. It uses the
     * same query as the retraction, so the projection cannot disagree with the write it predicts.
     */
    @Transactional(readOnly = true)
    fun claimedTypesOf(accountId: AccountId, methodInstanceId: String): Set<AttributeType> {
        val instanceId = runCatching { UUID.fromString(methodInstanceId) }.getOrNull() ?: return emptySet()
        return claimLedger.ownedBy(accountId, instanceId).map { it.first }.toSet()
    }

    /**
     * Withdraws one attribute of this account outright. [retractClaimsOf] cannot do this: an anchor
     * like EMAIL belongs to the account, not to a method, and `confirm-email` records it without an
     * `authMethodId`. The caller deals with what depended on the attribute. Per ADR-12 a retraction
     * row stops the value from counting, and the anchor row is deleted.
     *
     * @return true if something was established and is now withdrawn.
     */
    @Transactional
    fun retractAttribute(
        accountId: AccountId,
        attributeType: AttributeType,
        retractionSource: RetractionSource,
        reason: String? = null
    ): Boolean {
        // Checked here as well as by the caller, so no path to this method can widen what the
        // holder may give up (AnchorRule.retractableByHolder).
        check(retractionSource != RetractionSource.ACCOUNT_HOLDER || attributeType.anchorRule?.retractableByHolder == true) {
            "$attributeType cannot be withdrawn by the account holder"
        }
        // ADR-14: lock the account row so a concurrent confirm-email cannot interleave.
        lockForUpdate(accountId)
        // A value already withdrawn needs no second retraction row.
        if (!claimLedger.retractEstablished(accountId, attributeType, retractionSource, reason, clock.instant())) return false
        if (attributeType.isLocalAnchor) anchorRegistry.remove(accountId, attributeType)
        return true
    }

    /**
     * Retracts what a replaced singleton instance asserted and its replacement does not, e.g. the old
     * phone number. Values the replacement asserts itself stay: retractions work by value (ADR-12) and
     * the new claims are already recorded, so retracting them would cancel those as well.
     */
    private fun retractReplacedClaims(accountId: AccountId, replaced: List<UUID>, replacement: UUID, now: Instant) {
        if (replaced.isEmpty()) return
        val kept = claimLedger.ownedBy(accountId, replacement)
        replaced.flatMap { claimLedger.ownedBy(accountId, it) }.distinct().filterNot { it in kept }.forEach { (type, value) ->
            claimLedger.retract(accountId, type, value, RetractionSource.ACCOUNT_MANAGEMENT, "replaced", now)
        }
    }

    /**
     * Withdraws what one method instance asserted, one retraction row per distinct value (ADR-12).
     * Only claims with [AttributeAuthority.MethodModule] authority count. An anchor like `EMAIL`
     * belongs to the account and outlives the credential, or removing the email method would also
     * strip password login.
     *
     * @return how many retraction rows were written; 0 is ordinary for device or password.
     */
    @Transactional
    fun retractClaimsOf(
        accountId: AccountId,
        methodInstanceId: String,
        retractionSource: RetractionSource,
        reason: String? = null
    ): Int {
        val instanceId = runCatching { UUID.fromString(methodInstanceId) }.getOrNull() ?: return 0
        val now = clock.instant()
        val retractable = claimLedger.ownedBy(accountId, instanceId)
        retractable.forEach { (type, value) -> claimLedger.retract(accountId, type, value, retractionSource, reason, now) }
        return retractable.size
    }

    /**
     * Records the [claims] of one completed tool run (docs/02-domaenenmodell.md #6), at most one per
     * [AttributeType]. A local anchor attribute is also written to its [AccountAnchor]. An already
     * established (type, value, source, method) adds no row; re-proving is an IDENTIFIED event (ADR-39).
     * [provenAcr] is the session's capped level, not the tool's ceiling. It pays for anchor writes
     * ([AnchorRule.acrFloor]); a claim below the floor is still logged but does not move the anchor.
     */
    @Transactional
    fun recordClaims(
        accountId: AccountId,
        claims: List<Claim>,
        provenAcr: AcrLevel,
        authMethodId: UUID? = null
    ) {
        claimLedger.append(accountId, claims, provenAcr, authMethodId).forEach { (claim, establishedAt) ->
            if (claim.attributeType.isLocalAnchor) {
                lockForUpdate(accountId)
                anchorRegistry.bind(accountId, claim.attributeType, claim.value, establishedAt, provenAcr)
            }
        }
    }

    /**
     * Creates an account without a person binding. Identification binds it via [recordClaims] in the
     * same caller transaction, so a failed claim also rolls back the new account.
     */
    @Transactional
    fun createAccountInSetup(): AccountProfile {
        val account = accountRepository.save(Account(createdAt = clock.instant()))
        return AccountProfile(accountId = account.accountId, personId = null, authenticationMethods = emptyList())
    }

    /**
     * Moves everything the disposable account [from] established onto [into] and deletes it (ADR-20).
     * [from] must be disposable, re-checked here. The order is read, release, write: `ux_anchor_value`
     * makes anchors unique across accounts, so [from]'s anchors must be gone before the same values are
     * written on [into]. Claims are replayed one by one via [recordClaim], because the log may hold
     * several values of one attribute and a batch allows only one per attribute.
     */
    @Transactional
    fun absorbDisposableAccount(from: AccountId, into: AccountId) {
        check(from != into) { "absorbDisposableAccount($from): an account cannot absorb itself" }
        val source = findAccount(from) ?: error("Account not found: $from")
        if (!source.isDisposable) {
            throw IdentityConflictException(
                Text("Dieses Konto ist bereits vollstaendig angelegt und kann nicht in ein anderes Konto uebernommen werden"),
                "account $from is not disposable, cannot be absorbed into $into"
            )
        }
        checkNotNull(findAccount(into)) { "Account not found: $into" }

        val anchors = anchorRegistry.anchorsOf(from)
        // Each anchor keeps the level it was originally paid with (AnchorRule.acrFloor), not the
        // current session's, so absorbing neither under- nor overpays.
        val anchorAcr = anchors.mapNotNull { anchor ->
            anchor.attributeType?.let { type -> type to (anchor.establishedAcr?.let(AcrLevel::parse) ?: AcrLevel.NONE) }
        }.toMap()
        val claims = claimLedger.established(from).sortedBy { it.establishedAt }

        // Release the unique anchor values before the same values are written on `into`.
        anchorRegistry.releaseNow(anchors)
        changeLog.accountAbsorbed(into, from)
        deleteAccount(from)

        claims.forEach { claim ->
            val type = checkNotNull(claim.attributeType) { "Claim without an attribute type on account $from" }
            recordClaim(
                into,
                Claim(
                    attributeType = type,
                    value = checkNotNull(claim.value) { "Claim without a value on account $from" },
                    source = ClaimSource(checkNotNull(claim.claimSource) { "Claim without a source on account $from" }),
                    establishedAcr = claim.establishedAcr?.let(AcrLevel::parse)
                ),
                provenAcr = anchorAcr[type] ?: claim.establishedAcr?.let(AcrLevel::parse) ?: AcrLevel.NONE
            )
        }
        log.info("Account {} absorbed disposable account {} ({} claims)", into, from, claims.size)
    }

    /**
     * The audit record of one identification run (ADR-39): procedure, level, role (ADR-18) and where
     * to check it, never what it saw. Also stamps the search key over the verified name and date of
     * birth; self-reported values never count, or anyone could plant hits under someone else's name.
     * Called after the run's claims are recorded, so they are part of it.
     */
    @Transactional
    fun addIdentification(accountId: AccountId, method: String, loa: String?, role: String? = null, report: Map<String, Any?> = emptyMap()) {
        val verified = claimLedger.provenValues(accountId, PERSON_LOOKUP_ATTRIBUTES)
        val lookupKey = personLookupKey.of(
            verified[AttributeType.FAMILY_NAME], verified[AttributeType.GIVEN_NAMES], verified[AttributeType.BIRTH_DATE]?.let(LocalDate::parse)
        )
        val personId = anchorRegistry.valueOf(accountId, AttributeType.PERSON_ID)
        changeLog.identified(accountId, method, loa, role, report, lookupKey, personId?.let(::PartnerNumber))
    }

    /**
     * Adds a method instance. With [allowsMultipleInstances] (docs/03-tool-architektur.md) existing
     * active entries of [method] stay active; otherwise they are replaced. [label] is a user-chosen
     * name for multi-instance methods only.
     */
    @Transactional
    fun addAuthenticationMethod(
        accountId: AccountId,
        method: String,
        enrollmentRef: EnrollmentRef,
        enrolledUnderAcr: String?,
        details: Map<String, Any?>,
        /**
         * The session's proofs when the method was added. Audit evidence only (ADR-39): recorded with
         * the `METHOD_ADDED` event, which outlives the account, not in [details].
         */
        enrolledUnderAmr: List<String> = emptyList(),
        channel: String? = null,
        allowsMultipleInstances: Boolean = false,
        label: String? = null,
        /** Given by the enrollment path up front, so its claims can point at this instance (ADR-12). */
        instanceId: UUID = UUID.randomUUID()
    ): AccountProfile {
        lockForUpdate(accountId)
        val now = clock.instant()
        val active = accountAuthMethodRepository.findByAccountIdAndMethodAndActiveTrueOrderByCreatedAt(accountId, method)
        if (!allowsMultipleInstances) {
            // Re-enrolling a singleton method replaces the old credential. Two active entries of
            // one method would be read inconsistently.
            active.forEach {
                it.deactivate(now)
                changeLog.methodDeactivated(accountId, it.method, MethodDeactivationReason.REPLACED, now)
            }
            // Flushed before the new instance is inserted: the database allows one active instance
            // of a singleton method per account (ux_auth_method_active_singleton), and Hibernate
            // would otherwise insert before it updates.
            accountAuthMethodRepository.saveAllAndFlush(active)
            retractReplacedClaims(accountId, active.mapNotNull { it.id }, replacement = instanceId, now = now)
        } else if (active.any { it.enrollmentRef == enrollmentRef }) {
            // The same credential for the same account is a re-run of a completed enrollment
            // (docs/09-dpop.md). A second row would match every future lookup alike.
            return getProfileOrThrow(accountId)
        }
        changeLog.methodAdded(accountId, method, enrolledUnderAcr, enrolledUnderAmr, channel, now)
        accountAuthMethodRepository.save(
            AccountAuthMethod(
                accountId = accountId,
                method = method,
                enrollmentType = enrollmentRef.type,
                enrollmentId = enrollmentRef.id,
                enrolledUnderAcr = enrolledUnderAcr,
                label = label,
                details = details
            ).also { it.id = instanceId; it.createdAt = now }
        )
        return getProfileOrThrow(accountId)
    }

    /**
     * Deactivation by the holder (AuthIntent.MANAGE_AUTH_METHODS). The caller has checked the
     * channel's floor. Addressed by [methodInstanceId], since several devices share one method name.
     */
    @Transactional
    fun deactivateAuthenticationMethod(accountId: AccountId, methodInstanceId: String): AccountProfile {
        lockForUpdate(accountId)
        findMethodInstance(accountId, methodInstanceId)?.takeIf { it.active }?.let {
            val now = clock.instant()
            it.deactivate(now)
            changeLog.methodDeactivated(accountId, it.method, MethodDeactivationReason.REMOVED_BY_HOLDER, now)
        }
        return getProfileOrThrow(accountId)
    }

    @Transactional(readOnly = true)
    fun findAccount(accountId: AccountId): AccountProfile? =
        accountRepository.findAccount(accountId)?.let { toProfile(it) }

    /** For the demo admin pages only; the login path must not depend on a full list (10 million+ accounts). */
    @Transactional(readOnly = true)
    fun allAccountIds(): List<AccountId> = accountRepository.findAllIds()

    /**
     * Deletes the account row; anchors, methods and claims cascade. The change log survives without
     * values, and the retention period counts from this entry (ADR-39). This module must not depend
     * on a method module, so the caller first cleans up the credentials behind [allEnrollmentRefs].
     */
    @Transactional
    fun deleteAccount(accountId: AccountId) {
        changeLog.accountDeleted(accountId)
        accountRepository.deleteAccount(accountId)
        eventPublisher.publishEvent(AccountDeleted(accountId))
    }

    /**
     * Whether [accountId] exists and is still being set up: no login method yet (ADR-46,
     * [AccountProfile.isSetUp]). Two existence checks, no profile.
     */
    @Transactional(readOnly = true)
    fun isBeingSetUp(accountId: AccountId): Boolean =
        accountRepository.existsAccount(accountId) && !accountAuthMethodRepository.existsByAccountId(accountId)

    /** Accounts still being set up and created before [cutoff], oldest first, at most [limit] (ADR-46). */
    @Transactional(readOnly = true)
    fun accountsBeingSetUpCreatedBefore(cutoff: Instant, limit: Int): List<AccountId> =
        accountRepository.findIdsBeingSetUpCreatedBefore(cutoff, PageRequest.of(0, limit))

    /** Including deactivated methods, so account deletion also removes a replaced credential's row. */
    @Transactional(readOnly = true)
    fun allEnrollmentRefs(accountId: AccountId): List<EnrollmentRef> =
        accountAuthMethodRepository.findByAccountIdOrderByCreatedAt(accountId).map { it.enrollmentRef }

    /**
     * Whether another account's method points at the same credential row, e.g. a device key rebound
     * to a new account. Such a row must survive this account's deletion or revocation.
     */
    @Transactional(readOnly = true)
    fun isEnrollmentSharedWithOtherAccount(accountId: AccountId, enrollmentRef: EnrollmentRef): Boolean =
        accountAuthMethodRepository.existsByEnrollmentTypeAndEnrollmentIdAndAccountIdNot(enrollmentRef.type, enrollmentRef.id, accountId)

    /** For a caller revoking a single credential. */
    @Transactional(readOnly = true)
    fun enrollmentRefFor(accountId: AccountId, methodInstanceId: String): EnrollmentRef? =
        findMethodInstance(accountId, methodInstanceId)?.enrollmentRef

    @Transactional(readOnly = true)
    fun findActiveMethod(accountId: AccountId, method: String): AuthMethodView? =
        findActiveMethods(accountId, method).firstOrNull()

    /** All active instances of [method], e.g. one `device` entry per physical device. */
    @Transactional(readOnly = true)
    fun findActiveMethods(accountId: AccountId, method: String): List<AuthMethodView> =
        accountAuthMethodRepository.findByAccountIdAndMethodAndActiveTrueOrderByCreatedAt(accountId, method).map { it.toView() }

    // AccountDirectory (tool_api) -------------------------------------------------------------

    /**
     * The account holding anchor [type] = [value], registered or still being set up: for keeping
     * anchors and master data right, never for a login - that is [resolveByAnchor] (ADR-46).
     */
    @Transactional(readOnly = true)
    fun anchorHolder(type: AttributeType, value: String): AccountId? = anchorRegistry.holderOf(type, value)

    /** Only accounts that are set up: one without a login method is not there for a login (ADR-46). */
    override fun resolveByAnchor(type: AttributeType, value: String): AccountId? =
        anchorRegistry.holderOf(type, value)?.takeIf { accountAuthMethodRepository.existsByAccountId(it) }

    override fun anchorValue(accountId: AccountId, type: AttributeType): String? {
        check(type.isLocalAnchor) { "$type is not a local account anchor, it is owned by ${type.authority}" }
        return anchorRegistry.valueOf(accountId, type)
    }

    /**
     * The established claim values for [types], strongest assertion per attribute. The value-reading
     * counterpart of [AccountProfile.establishedClaims], e.g. for the ID-token name of a prospect
     * without a register person (ADR-18).
     */
    fun establishedClaimValues(accountId: AccountId, types: Set<AttributeType>): Map<AttributeType, String> =
        claimLedger.establishedValues(accountId, types)

    override fun activeEnrollment(accountId: AccountId, method: String): EnrollmentRef? =
        findActiveMethod(accountId, method)?.enrollmentRef

    override fun activeInstanceEnrollment(accountId: AccountId, method: String, livesOnCallerKey: (instanceDetails: Map<String, Any?>?) -> Boolean): EnrollmentRef? =
        findActiveMethods(accountId, method).firstOrNull { livesOnCallerKey(it.details) }?.enrollmentRef

    /**
     * Follows a change the Personenverzeichnis reported (ADR-34). KVNR and Versicherungsnummer are the
     * only values the account stores itself: the old one is retracted, the new one recorded. There is
     * no session here, so the anchor write is paid with [DIRECTORY_ACR]; the directory is the authority
     * for these two identifiers. This exception applies only here.
     *
     * @return the account that followed the change, or null when nobody is bound to the person.
     */
    @Transactional
    fun applyDirectoryChange(change: PersonChanged): AccountId? {
        // An account still being set up follows the register too (ADR-46).
        val accountId = anchorHolder(AttributeType.PERSON_ID, change.personId.value) ?: return null
        if (AttributeType.KVNR in change.changed) {
            retractAttribute(accountId, AttributeType.KVNR, RetractionSource.PERSON_DIRECTORY, "KVNR im Personenverzeichnis geändert")
            change.kvnr?.let {
                recordClaims(accountId, listOf(Claim(AttributeType.KVNR, it, ClaimSource.PERSON_DIRECTORY, DIRECTORY_ACR)), DIRECTORY_ACR)
            }
        }
        if (AttributeType.MEMBER_NUMBER in change.changed) {
            retractAttribute(accountId, AttributeType.MEMBER_NUMBER, RetractionSource.PERSON_DIRECTORY, "Versicherungsnummer im Personenverzeichnis geändert")
            change.memberNumber?.let {
                releaseFromOtherAccount(AttributeType.MEMBER_NUMBER, it, keeper = accountId)
                recordClaims(accountId, listOf(Claim(AttributeType.MEMBER_NUMBER, it, ClaimSource.PERSON_DIRECTORY, DIRECTORY_ACR)), DIRECTORY_ACR)
            }
        }
        return accountId
    }

    /**
     * Another account still holding [value] holds a stale value: the directory moved it (ADR-34), and
     * that account's own change event may not have arrived yet. It is withdrawn there, or the change
     * would fail on the anchor conflict and be retried forever.
     */
    private fun releaseFromOtherAccount(type: AttributeType, value: String, keeper: AccountId) {
        val previousHolder = anchorRegistry.holderOf(type, value) ?: return
        if (previousHolder == keeper) return
        log.info("{} anchor moved by the Personenverzeichnis: released from account {} for account {}", type.wireName, previousHolder, keeper)
        retractAttribute(previousHolder, type, RetractionSource.PERSON_DIRECTORY, "Im Personenverzeichnis einer anderen Person zugeordnet")
    }

    private fun lockForUpdate(accountId: AccountId): Account =
        accountRepository.findForUpdate(accountId) ?: error("Account not found: $accountId")

    private fun findMethodInstance(accountId: AccountId, methodInstanceId: String): AccountAuthMethod? {
        val id = runCatching { UUID.fromString(methodInstanceId) }.getOrNull() ?: return null
        return accountAuthMethodRepository.findByIdAndAccountId(id, accountId)
    }

    private fun getProfileOrThrow(accountId: AccountId): AccountProfile =
        findAccount(accountId) ?: error("Account not found: $accountId")

    private fun toProfile(account: Account): AccountProfile {
        val accountId = account.accountId
        val anchors = anchorRegistry.anchorsOf(accountId).associateBy { it.attributeType }
        val emailAnchor = anchors[AttributeType.EMAIL]
        return AccountProfile(
            accountId = accountId,
            personId = anchors[AttributeType.PERSON_ID]?.value?.let(::PartnerNumber),
            authenticationMethods = accountAuthMethodRepository.findByAccountIdOrderByCreatedAt(accountId).map { it.toView() },
            email = emailAnchor?.value,
            emailConfirmedAt = emailAnchor?.establishedAt,
            establishedClaims = claimLedger.establishedTrust(accountId)
        )
    }

    private fun AccountAuthMethod.toView() = AuthMethodView(
        id = id.toString(),
        method = method.orEmpty(),
        active = active,
        createdAt = createdAt,
        enrolledUnderAcr = enrolledUnderAcr,
        details = details,
        enrollmentRef = enrollmentRef,
        label = label
    )

    private companion object {
        /** What the Personenverzeichnis' own word counts as for [applyDirectoryChange] - the anchor floor of both identifiers. */
        val DIRECTORY_ACR = AcrLevel.LOA2

        /** What a person can still tell us years later - the input of [PersonLookupKey]. */
        val PERSON_LOOKUP_ATTRIBUTES = setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
    }
}
