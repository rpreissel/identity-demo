package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.KeycloakToolCalls
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.assertClaimsCovered
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidationException
import com.example.identity.core.orchestrator.session.AccountLockoutService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Books Keycloak's channel-less tool calls. With no session there is no evidence to cap an
 * enrollment by (ADR-5): the instance carries no `enrolledUnderAcr`, and its claims are written
 * under the default level, as for any native Keycloak credential.
 */
@Service
@Transactional
class KeycloakToolCallsService(
    private val accountLockoutService: AccountLockoutService,
    private val accountService: AccountService,
) : KeycloakToolCalls {

    override fun requireKeycloakFor(accountId: AccountId, bindingKeyRef: String) {
        if (bindingKeyRef != DeviceChannelAccessGuard.KEYCLOAK_BINDING_PREFIX + accountId) {
            throw PeerAuthValidationException("Peer-auth channel_binding does not match accountId")
        }
    }

    override fun apply(accountId: AccountId, descriptor: ToolDescriptor, outcome: ToolOutcome) {
        when (outcome) {
            is ToolOutcome.Failed.KnownAccountAuth -> {
                checkFits(outcome.fits(descriptor.role), descriptor, outcome)
                accountLockoutService.recordFailure(accountId, ChannelType.WEB.name, descriptor.method)
            }
            is ToolOutcome.Completed.Authenticated -> {
                checkFits(outcome.fits(descriptor.role), descriptor, outcome)
                checkStaysWithin(descriptor, outcome)
                accountLockoutService.recordSuccess(accountId)
            }
            is ToolOutcome.Completed.Enrolled -> {
                checkFits(outcome.fits(descriptor.role), descriptor, outcome)
                checkStaysWithin(descriptor, outcome)
                assertClaimsCovered(descriptor, outcome.claims)
                // The claim naming the instance first, then the instance, as in a journey: without
                // the claim a later revocation would leave "has a password" standing.
                val instanceId = UUID.randomUUID()
                accountService.recordClaims(accountId, outcome.claims, provenAcr = AcrLevels.DEFAULT_REQUIRED_ACR, authMethodId = instanceId)
                accountService.addAuthenticationMethod(
                    accountId = accountId,
                    method = descriptor.method,
                    enrollmentRef = outcome.enrollmentRef,
                    enrolledUnderAcr = null,
                    details = outcome.instanceDetails,
                    instanceId = instanceId
                )
            }
            else -> error("${descriptor.toolId}: ${outcome::class.simpleName} is not bookable without a journey")
        }
    }

    private fun checkFits(fits: Boolean, descriptor: ToolDescriptor, outcome: ToolOutcome) =
        check(fits) { "${descriptor.toolId} (${descriptor.role}) answered with ${outcome::class.simpleName}" }
}
