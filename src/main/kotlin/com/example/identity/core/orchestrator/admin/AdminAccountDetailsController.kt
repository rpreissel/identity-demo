package com.example.identity.core.orchestrator.admin

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.MemberNumber
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.AccountInspection
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.AnchorRecord
import com.example.identity.core.account.ChangeLogRecord
import com.example.identity.core.account.RetractionRecord
import com.example.identity.core.account.SignInRecord
import com.example.identity.core.account.findAccountByEmail
import com.example.identity.core.account.findAccountByKvnr
import com.example.identity.core.account.findAccountByPersonId
import com.example.identity.core.orchestrator.session.LinkedDevice
import com.example.identity.core.orchestrator.session.SessionManagementService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.Instant

/** What the operator typed to find a person. */
data class AdminAccountLookupRequest(val query: String)

/** What a lookup value matched. */
enum class AdminAccountMatchKind { EMAIL, KVNR, PARTNER_NUMBER, MEMBER_NUMBER, ACCOUNT_NUMBER }

data class AdminAccountMatch(val accountId: AccountId, val displayName: String?, val matchedBy: AdminAccountMatchKind)

/** Empty when no account holds the value; more than one when eight digits match two ways. */
data class AdminAccountLookupResponse(val matches: List<AdminAccountMatch>)

/** The three roles of an account (docs/glossar): insured with us, known as a person, or neither. */
enum class AdminAccountRole { INSURED, PARTNER, PROSPECT }

/**
 * One entry of the claim log. [maskedValue] shows only the first characters; the full values come
 * from `claim-values`, which is logged. [readable] is false once the value is erased (ADR-52).
 */
data class AdminClaim(
    val id: Long,
    val attributeType: String,
    val maskedValue: String?,
    val readable: Boolean,
    val claimSource: String?,
    val establishedAcr: String?,
    /** The method whose enrollment asserted the claim, by its method name; null for identification. */
    val method: String?,
    val establishedAt: Instant,
    val retraction: RetractionRecord?,
)

data class AdminMethod(
    val id: String,
    val method: String,
    val label: String?,
    val active: Boolean,
    val enrolledUnderAcr: String?,
    /** Set for a method bound to one device (its binding key). */
    val boundKeyRef: String?,
    val createdAt: Instant?,
    val deactivatedAt: Instant?,
)

data class AdminAccountDetails(
    val accountId: AccountId,
    val displayName: String?,
    val role: AdminAccountRole,
    val createdAt: Instant?,
    val anchors: List<AnchorRecord>,
    val claims: List<AdminClaim>,
    val retractions: List<RetractionRecord>,
    val methods: List<AdminMethod>,
    val devices: List<LinkedDevice>,
    @field:Schema(description = "The account's change log (ADR-39), oldest first; it outlives the account.")
    val changeLog: List<ChangeLogRecord>,
    val signIns: List<SignInRecord>,
)

/** The full values of an account's readable claims, by claim id. */
data class AdminClaimValues(val values: Map<Long, String>)

/**
 * The operator's account view (docs/10-frontend.md #6): find an account by an identifier, then see
 * what it knows and where each value came from. Not demo-only: support needs it in production.
 * There is no list of all accounts; a person is found by one indexed lookup.
 */
@RestController
@RequestMapping(ADMIN_API)
@Tag(name = "Admin: account details", description = "Find an account and inspect its claims, anchors, methods and logs")
class AdminAccountDetailsController(
    private val accountService: AccountService,
    private val accountInspection: AccountInspection,
    private val personDirectory: PersonDirectory,
    private val sessionManagementService: SessionManagementService,
) {
    private val log = LoggerFactory.getLogger(AdminAccountDetailsController::class.java)

    /**
     * Finds the accounts behind what the operator typed: an email address, a KVNR, a partner
     * number, a Versicherungsnummer or an account number - each one indexed lookup. Eight digits
     * can be a Versicherungsnummer and an account number at once, so the answer is a list, each
     * match saying what it matched. A POST, so the value stays out of URLs and access logs. Names
     * are not searchable: they live only in the person directory, which offers no name search,
     * and are not unique.
     */
    @PostMapping("/account-lookups")
    @Operation(
        summary = "Find the accounts behind an email address, KVNR, partner number, Versicherungsnummer or account number",
        description = "Indexed lookups only; each match names what it matched (`matchedBy`). Names are not searchable."
    )
    fun lookupAccount(@RequestBody request: AdminAccountLookupRequest): AdminAccountLookupResponse {
        val query = request.query.trim()
        val upper = query.uppercase()
        val found: List<Pair<AdminAccountMatchKind, AccountProfile?>> = when {
            '@' in query -> listOf(AdminAccountMatchKind.EMAIL to runCatching { accountService.findAccountByEmail(query) }.getOrNull())
            // A KVNR starting with P has the shape of a partner number.
            PARTNER_NUMBER.matches(upper) -> listOf(
                AdminAccountMatchKind.PARTNER_NUMBER to accountService.findAccountByPersonId(PartnerNumber(upper)),
                AdminAccountMatchKind.KVNR to accountService.findAccountByKvnr(query, personDirectory)
            )
            KVNR.matches(upper) -> listOf(AdminAccountMatchKind.KVNR to accountService.findAccountByKvnr(query, personDirectory))
            DIGITS.matches(query) -> listOfNotNull(
                MemberNumber.parse(query)?.let { memberNumber ->
                    AdminAccountMatchKind.MEMBER_NUMBER to accountService.anchorHolder(AttributeType.MEMBER_NUMBER, memberNumber.value)
                        ?.let(accountService::findAccount)
                },
                query.toLongOrNull()?.let { AdminAccountMatchKind.ACCOUNT_NUMBER to accountService.findAccount(AccountId(it)) }
            )
            else -> throw InvalidInputException(
                Text("Gesucht wird nach E-Mail-Adresse, KVNR, Partnernummer, Versicherungsnummer oder Kontonummer.")
            )
        }
        val matches = found.mapNotNull { (kind, profile) -> profile?.let { kind to it } }
            .distinctBy { (_, profile) -> profile.accountId }
            .map { (kind, profile) -> AdminAccountMatch(profile.accountId, displayNameOf(profile), kind) }
        return AdminAccountLookupResponse(matches)
    }

    @GetMapping("/accounts/{accountId}/details")
    @Operation(
        summary = "One account in detail",
        description = "Anchors, the whole claim log with retractions (values masked), methods incl. removed ones, linked devices, " +
            "change log and sign-in log. 404 when the account does not exist."
    )
    fun details(@PathVariable accountId: AccountId): ResponseEntity<AdminAccountDetails> {
        val inspection = accountInspection.inspect(accountId) ?: return ResponseEntity.notFound().build()
        val profile = inspection.profile
        val methodNames = profile.authenticationMethods.associate { it.id to it.method }
        val anchorTypes = inspection.anchors.map { it.attributeType }.toSet()
        return ResponseEntity.ok(
            AdminAccountDetails(
                accountId = accountId,
                displayName = displayNameOf(profile),
                role = when {
                    AttributeType.MEMBER_NUMBER.wireName in anchorTypes -> AdminAccountRole.INSURED
                    profile.personId != null -> AdminAccountRole.PARTNER
                    else -> AdminAccountRole.PROSPECT
                },
                createdAt = inspection.createdAt,
                anchors = inspection.anchors,
                claims = inspection.claims.map { claim ->
                    AdminClaim(
                        id = claim.id,
                        attributeType = claim.attributeType,
                        maskedValue = claim.value?.let(::mask),
                        readable = claim.value != null,
                        claimSource = claim.claimSource,
                        establishedAcr = claim.establishedAcr,
                        method = claim.authMethodId?.let(methodNames::get),
                        establishedAt = claim.establishedAt,
                        retraction = claim.retraction,
                    )
                },
                retractions = inspection.retractions,
                methods = profile.authenticationMethods.map {
                    AdminMethod(it.id, it.method, it.label, it.active, it.enrolledUnderAcr, it.boundKeyRef, it.createdAt, it.deactivatedAt)
                },
                devices = sessionManagementService.linkedDevicesOf(accountId),
                changeLog = inspection.changeLog,
                signIns = inspection.signIns,
            )
        )
    }

    /**
     * The full claim values. A separate call, so reading personal data is a deliberate step, and
     * logged with who read which account (never the values themselves).
     */
    @PostMapping("/accounts/{accountId}/claim-values")
    @Operation(summary = "The full values of an account's claims", description = "Logged with the operator and the account id.")
    fun claimValues(@PathVariable accountId: AccountId, principal: Principal?): ResponseEntity<AdminClaimValues> {
        val inspection = accountInspection.inspect(accountId) ?: return ResponseEntity.notFound().build()
        log.info("Admin {} read the claim values of account {}", principal?.name ?: "unknown", accountId)
        return ResponseEntity.ok(AdminClaimValues(inspection.claims.mapNotNull { claim -> claim.value?.let { claim.id to it } }.toMap()))
    }

    private fun displayNameOf(profile: AccountProfile): String? = profile.personId?.let(personDirectory::displayName)

    private companion object {
        val PARTNER_NUMBER = Regex("^P\\d{9}$")
        /** One letter and nine digits (normalizeKvnr upper-cases). */
        val KVNR = Regex("^[A-Z]\\d{9}$")
        val DIGITS = Regex("^\\d{1,18}$")

        /** The first two characters; the rest hidden, without telling its length. */
        fun mask(value: String): String = if (value.length <= 4) "•••" else value.take(2) + "•••"
    }
}
