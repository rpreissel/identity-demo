package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.orchestrator.domain.policy.fromNow
import com.example.identity.TEST_CLOCK
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.tools.auth_device.AuthDeviceDescriptor
import com.example.identity.tools.auth_device.EnrollDeviceDescriptor
import com.example.identity.tools.auth_kobil.AuthKobilDescriptor
import com.example.identity.tools.auth_kobil.EnrollKobilDescriptor
import com.example.identity.tools.auth_email.AuthEmailLookupDescriptor
import com.example.identity.tools.auth_email.AuthEmailDescriptor
import com.example.identity.tools.auth_email.ConfirmEmailDescriptor
import com.example.identity.tools.auth_email.EnrollEmailDescriptor
import com.example.identity.tools.auth_password.AuthPasswordLookupDescriptor
import com.example.identity.tools.auth_password.AuthPasswordDescriptor
import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
import com.example.identity.tools.auth_qr.AuthQrDescriptor
import com.example.identity.tools.auth_qr.AuthQrLookupDescriptor
import com.example.identity.tools.auth_invite.AuthInviteDescriptor
import com.example.identity.tools.auth_qr.ConfirmQrLoginDescriptor
import com.example.identity.tools.auth_qr.EnrollQrDescriptor
import com.example.identity.tools.auth_sms.AuthSmsLookupDescriptor
import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.auth_sms.EnrollSmsDescriptor
import com.example.identity.tools.ident_eid.IdentEidDescriptor
import com.example.identity.tools.ident_nect.IdentNectDescriptor
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.tools.ident_kvnr.IdentKvnrDescriptor
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.DefaultAuthPolicy
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.ClaimTrust
import java.time.Instant

/**
 * Shared fixtures for [IntentStrategy] unit tests, built on the real catalog (every module's
 * `Descriptors.kt` object), so strategies see the same candidate resolution as production
 * (docs/03-tool-architektur.md #1). The descriptors are plain objects; no Spring context is needed.
 */
object StrategyTestFixtures {

    val catalog = ToolHandlerRegistry(
        listOf(
            IdentFscDescriptor, IdentEidDescriptor, IdentNectDescriptor, IdentKvnrDescriptor,
            EnrollSmsDescriptor, AuthSmsDescriptor, AuthSmsLookupDescriptor,
            ConfirmEmailDescriptor, EnrollEmailDescriptor, AuthEmailDescriptor, AuthEmailLookupDescriptor,
            EnrollPasswordDescriptor, AuthPasswordDescriptor, AuthPasswordLookupDescriptor,
            EnrollDeviceDescriptor, AuthDeviceDescriptor,
            EnrollKobilDescriptor, AuthKobilDescriptor,
            EnrollQrDescriptor, AuthQrDescriptor, AuthQrLookupDescriptor, ConfirmQrLoginDescriptor,
            AuthInviteDescriptor
        )
    )
    val policy = DefaultAuthPolicy(catalog, TEST_CLOCK)
    val allToolIds: Set<ToolId> = catalog.descriptors().map { it.toolId }.toSet()

    /** The App channel with the shipped defaults: everything declared, minus `tool-defaults.channels.APP.disabled`. */
    val appTools: Set<ToolId> = allToolIds - listOf("auth-qr", "auth-email", "auth-qr-lookup", "auth-email-lookup").map(::ToolId).toSet()

    /**
     * The Web channel with the shipped defaults: what the Keycloak theme renders (no Nect), minus
     * `tool-defaults.channels.WEB.disabled`.
     */
    val webTools: Set<ToolId> = allToolIds - listOf(
        "ident-nect", "enroll-device", "auth-device", "enroll-kobil", "auth-kobil", "auth-email", "auth-email-lookup"
    ).map(::ToolId).toSet()

    const val BINDING_KEY = "test-binding-key"

    /**
     * [attestedIdentity] mirrors what an attestation (`ident-eid`) leaves on an account: the claims
     * `ident-kvnr`'s `requires` is checked against. Off by default.
     */
    fun account(
        vararg methods: AuthMethodView,
        accountId: AccountId = AccountId(1L),
        personId: PartnerNumber? = PartnerNumber("P000000001"),
        emailConfirmed: Boolean = true,
        attestedIdentity: Boolean = false
    ) = AccountProfile(
        accountId = accountId,
        personId = personId,
        authenticationMethods = methods.toList(),
        emailConfirmedAt = if (emailConfirmed) Instant.now() else null,
        establishedClaims = buildMap {
            // A confirmed address is an EMAIL claim at PROVEN; the anchor is only its projection.
            if (emailConfirmed) put(AttributeType.EMAIL, ClaimTrust.PROVEN)
            if (attestedIdentity) {
                put(AttributeType.FAMILY_NAME, ClaimTrust.PROVEN)
                put(AttributeType.GIVEN_NAMES, ClaimTrust.PROVEN)
                put(AttributeType.BIRTH_DATE, ClaimTrust.PROVEN)
            }
        }
    )

    fun method(
        method: String,
        enrolledUnderAcr: AcrLevel,
        active: Boolean = true,
        details: Map<String, Any?>? = null
    ) = AuthMethodView(
        id = "$method-instance", method = method, active = active,
        createdAt = null, enrolledUnderAcr = enrolledUnderAcr.value, details = details,
        enrollmentRef = EnrollmentRef("${method}_enrollment", "1")
    )

    /** A device credential's `details` map, matching what `CandidateTools.preferredDeviceAuth` looks for. */
    fun deviceDetails(bindingKeyRef: String = BINDING_KEY): Map<String, Any?> = mapOf("deviceBindingKeyRef" to bindingKeyRef)

    /**
     * Derives each method's loa from the real catalog (its highest maxAcr), as JourneyService does
     * before calling AuthPolicy. Given an [account], its enrollment record feeds the MFA-combination
     * bump. [amrSourceId] names the tool behind an amr value where the two differ:
     * ident-nect reports `nect-<procedure>`, not its method name.
     */
    fun evidence(
        amr: List<String>,
        factorTypes: Set<FactorType>,
        account: AccountProfile? = null,
        amrSourceId: Map<String, String> = emptyMap()
    ): SessionEvidence {
        val methodAcr = amr.associateWith { m ->
            catalog.descriptors().filter { it.method == m }.maxByOrNull { AcrLevel.rank(it.maxAcr) }?.maxAcr?.value ?: AcrLevel.NONE.value
        }
        val enrolledUnderAcr = account?.authenticationMethods
            ?.filter { it.method in amr }
            ?.mapNotNull { m -> m.enrolledUnderAcr?.let { m.method to it } }
            ?.toMap()
            ?: emptyMap()
        return SessionEvidence.fromNow(amr, factorTypes, methodAcr, enrolledUnderAcr, amrSourceId = amrSourceId)
    }

    fun ctx(
        account: AccountProfile? = null,
        evidence: SessionEvidence = SessionEvidence(emptyList()),
        acrFloor: AcrLevel = AcrLevel.LOA1,
        bindingKeyRef: String = BINDING_KEY,
        // Defaults to a device linked to the context's own account; a device-rebind conflict test
        // (docs/09-dpop.md) passes a different accountId.
        linkedAccountId: AccountId? = account?.accountId,
        isSubJourney: Boolean = false,
        availableTools: Set<ToolId> = allToolIds,
        channel: ChannelType = ChannelType.APP
    ) = JourneyContext(channel, account, evidence, acrFloor, bindingKeyRef, linkedAccountId, isSubJourney, policy, catalog, availableTools)
}
