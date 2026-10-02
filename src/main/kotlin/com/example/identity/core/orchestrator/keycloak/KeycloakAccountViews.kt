package com.example.identity.core.orchestrator.keycloak

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.contract.tool_api.directory.PersonMasterData
import com.example.identity.contract.tool_api.directory.PersonRecord
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.values.Email
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * An account as Keycloak sees it, read live on every lookup and never copied into Keycloak (ADR-38).
 * Keycloak's user federation asks for it by account id, email or username. The username is the
 * confirmed email, else `account-<id>`, which stays stable for an account without an address.
 */
data class KeycloakAccountView(
    val accountId: AccountId,
    val username: String,
    val email: String?,
    val emailVerified: Boolean,
    val firstName: String,
    val lastName: String,
    /** `orchestratorAccountId` plus the person attributes behind the token claims (person_id, versnr, ...). */
    val attributes: Map<String, String>,
)

@Component
@Transactional(readOnly = true)
class KeycloakAccountViews(
    private val accountService: AccountService,
    private val personMasterData: PersonMasterData,
) {
    fun byAccountId(accountId: AccountId): KeycloakAccountView? = accountService.findAccount(accountId)?.let(::viewOf)

    /**
     * Keycloak asks every federation for any name it meets, among them `invitation-<id>` of the
     * invitation federation (ADR-48) and whatever someone typed into the password form. A name that
     * is no email address is simply nobody here, not a bad request.
     */
    fun byEmail(email: String): KeycloakAccountView? {
        if (Email.parse(email) == null) return null
        return accountService.resolveByAnchor(AttributeType.EMAIL, email.trim())?.let(::byAccountId)
    }

    /** `account-<id>` or an email - the two forms [KeycloakAccountView.username] takes. */
    fun byUsername(username: String): KeycloakAccountView? =
        username.removePrefix(USERNAME_PREFIX).takeIf { it != username }?.toLongOrNull()?.let { byAccountId(AccountId(it)) }
            ?: byEmail(username)

    private fun viewOf(profile: AccountProfile): KeycloakAccountView {
        val person = profile.personId?.let { personMasterData.masterDataOf(it) }
        val names = keycloakUserMirror(profile, person, accountService.establishedClaimValues(profile.accountId, MIRRORED_CLAIM_TYPES))
        return KeycloakAccountView(
            accountId = profile.accountId,
            username = profile.email ?: "$USERNAME_PREFIX${profile.accountId}",
            email = profile.email,
            emailVerified = profile.emailConfirmed,
            firstName = names.firstName,
            lastName = names.lastName,
            attributes = names.attributes + (ACCOUNT_ID_ATTRIBUTE to profile.accountId.toString()),
        )
    }

    companion object {
        const val USERNAME_PREFIX = "account-"
        const val ACCOUNT_ID_ATTRIBUTE = "orchestratorAccountId"
    }
}

/**
 * Keycloak requires a first and last name on every user. This stands in for an account with
 * neither a [PersonRecord] nor attested name claims. Read live, so the real name replaces it.
 */
internal const val UNIDENTIFIED_FIRST_NAME = "Unbekannt"
internal const val UNIDENTIFIED_LAST_NAME = "(nicht identifiziert)"

/**
 * The attested claim types Keycloak shows for an account without a bound person: the person
 * attributes an eID attestation can carry.
 */
internal val MIRRORED_CLAIM_TYPES = setOf(
    AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE,
    AttributeType.STREET_ADDRESS, AttributeType.POSTAL_CODE, AttributeType.LOCALITY
)

/** Names and attributes of one account as Keycloak shows them. */
internal data class KeycloakUserMirror(
    val firstName: String,
    val lastName: String,
    val attributes: Map<String, String>
)

/**
 * Names and attributes for the Keycloak user mirror. For a bound account the Personenverzeichnis is
 * the only source, including the fields it leaves empty, so a cleared field stays cleared (ADR-34).
 * An prospect (ADR-18) is mirrored from its own established claims. `personId`, `kvnr` and
 * `versnr` exist only for a bound account; their absence marks a prospect.
 */
internal fun keycloakUserMirror(profile: AccountProfile, person: PersonRecord?, attested: Map<AttributeType, String>): KeycloakUserMirror =
    KeycloakUserMirror(
        firstName = (if (person != null) person.givenNames else attested[AttributeType.GIVEN_NAMES]) ?: UNIDENTIFIED_FIRST_NAME,
        lastName = (if (person != null) person.familyName else attested[AttributeType.FAMILY_NAME]) ?: UNIDENTIFIED_LAST_NAME,
        attributes = masterDataAttributes(profile.personId, person, attested)
    )

/**
 * The person attributes as custom user attributes: from the Personenverzeichnis for a bound account
 * (never topped up from claims), from the account's attested claims for a prospect.
 */
internal fun masterDataAttributes(personId: PartnerNumber?, person: PersonRecord?, attested: Map<AttributeType, String>): Map<String, String> = buildMap {
    personId?.let { put("personId", it.value) }
    if (person != null) {
        person.kvnr?.let { put("kvnr", it) }
        person.memberNumber?.let { put("versnr", it) }
        person.birthDate?.let { put("birthDate", it.toString()) }
        // One street line - the port already joins the Personenverzeichnis' two fields.
        person.streetAddress?.let { put("streetAddress", it) }
        person.postalCode?.let { put("postalCode", it) }
        person.locality?.let { put("locality", it) }
    } else {
        attested[AttributeType.BIRTH_DATE]?.let { put("birthDate", it) }
        attested[AttributeType.STREET_ADDRESS]?.let { put("streetAddress", it) }
        attested[AttributeType.POSTAL_CODE]?.let { put("postalCode", it) }
        attested[AttributeType.LOCALITY]?.let { put("locality", it) }
    }
}
