package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.orchestrator.domain.policy.fromNow
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.tools.auth_device.DeviceModule
import com.example.identity.tools.auth_email.EmailModule
import com.example.identity.tools.auth_invite.InviteModule
import com.example.identity.tools.auth_kobil.KobilModule
import com.example.identity.tools.auth_password.PasswordModule
import com.example.identity.tools.auth_qr.QrModule
import com.example.identity.tools.auth_sms.SmsModule
import com.example.identity.tools.ident_eid.EidModule
import com.example.identity.tools.ident_fsc.FscModule
import com.example.identity.tools.ident_kvnr.KvnrModule
import com.example.identity.tools.ident_nect.NectModule
import com.example.identity.contract.tool_api.Tool
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
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * Shared fixtures for [IntentStrategy] unit tests, built on the real catalog (every module's
 * `ToolModule`), so strategies see the same candidate resolution as production
 * (docs/03-tool-architektur.md #1). The modules are plain values; no Spring context is needed.
 */
object StrategyTestFixtures {

    /** Every procedure, in the order the catalog lists them. */
    val modules = listOf(
        FscModule, EidModule, NectModule, KvnrModule, SmsModule, EmailModule, PasswordModule,
        DeviceModule, KobilModule, QrModule, InviteModule,
    )

    val catalog = ToolHandlerRegistry(modules)

    /** The tool with [toolId] from the real catalog, e.g. `tool("auth-sms")`. */
    fun tool(toolId: String): Tool = catalog.toolOf(ToolId(toolId))

    /** A catalog of just the procedures behind [toolIds], each with all its tools. */
    fun catalogOf(vararg toolIds: String): ToolHandlerRegistry = ToolHandlerRegistry(toolIds.map { tool(it).module }.distinct())

    val policy = DefaultAuthPolicy(catalog, TEST_CLOCK)
    val allToolIds: Set<ToolId> = catalog.tools().map { it.toolId }.toSet()

    /** The App channel with the shipped defaults: everything declared, minus `tool-defaults.channels.APP.disabled`. */
    val appTools: Set<ToolId> = allToolIds - listOf("auth-qr", "auth-email", "auth-qr-lookup", "auth-email-lookup").map(::ToolId).toSet()

    /**
     * The Web channel with the shipped defaults: what the Keycloak theme renders (no Nect), minus
     * `tool-defaults.channels.WEB.disabled`.
     */
    val webTools: Set<ToolId> = allToolIds - listOf(
        "ident-nect", "enroll-device", "auth-device", "enroll-kobil", "auth-kobil", "auth-email", "auth-email-lookup"
    ).map(::ToolId).toSet()

    /** What an sms-only account is offered on the App channel to add a factor kind, in catalog order. */
    val APP_SECOND_FACTOR_KINDS = listOf(ToolId("enroll-password"), ToolId("enroll-device"), ToolId("enroll-kobil"))

    const val BINDING_KEY = "test-binding-key"

    /** What an identification tool reports for the person behind [account]'s default personId. */
    fun identifiedOutcome() = ToolOutcome.Completed.Identified(
        claims = listOf(Claim(AttributeType.PERSON_ID, "P000000001", ClaimSource.PERSON_DIRECTORY))
    )

    /** What `confirm-email` reports: the address, no credential. */
    fun emailAttestation() = ToolOutcome.Completed.Attested(
        claims = listOf(Claim(AttributeType.EMAIL, "max@example.com", ClaimSource("confirm-email")))
    )

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
        emailConfirmedAt = if (emailConfirmed) TEST_NOW else null,
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
        boundKeyRef: String? = null,
        reference: String? = null,
    ) = AuthMethodView(
        id = "$method-instance", method = method, active = active,
        createdAt = null, enrolledUnderAcr = enrolledUnderAcr.value, boundKeyRef = boundKeyRef, reference = reference,
        enrollmentRef = EnrollmentRef("${method}_enrollment", "1")
    )

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
            catalog.tools().filter { it.method == m }.maxByOrNull { AcrLevel.rank(it.maxAcr) }?.maxAcr?.value ?: AcrLevel.NONE.value
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
