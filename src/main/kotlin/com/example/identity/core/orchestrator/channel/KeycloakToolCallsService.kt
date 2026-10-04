package com.example.identity.core.orchestrator.channel

import com.example.identity.core.orchestrator.journey.JourneyEndedException
import com.example.identity.core.orchestrator.session.ChannelSessionEndedException
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.KeycloakToolCalls
import com.example.identity.contract.tool_api.ToolModule
import com.example.identity.contract.tool_api.ToolRole
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
// ADR-43, I-2, see JourneyService
@Transactional(noRollbackFor = [ChannelSessionEndedException::class, JourneyEndedException::class])
class KeycloakToolCallsService(
    private val accountLockoutService: AccountLockoutService,
    private val accountService: AccountService,
) : KeycloakToolCalls {

    override fun requireKeycloakFor(accountId: AccountId, bindingKeyRef: String) {
        if (bindingKeyRef != DeviceChannelAccessGuard.KEYCLOAK_BINDING_PREFIX + accountId) {
            throw PeerAuthValidationException("Peer-auth channel_binding does not match accountId")
        }
    }

    override fun apply(accountId: AccountId, module: ToolModule, outcome: ToolOutcome) {
        val role = when (outcome) {
            is ToolOutcome.Failed.KnownAccountAuth, is ToolOutcome.Completed.Authenticated -> ToolRole.KNOWN_ACCOUNT_AUTH
            is ToolOutcome.Completed.Enrolled -> ToolRole.ENROLLMENT
            else -> error("${module.method}: ${outcome::class.simpleName} is not bookable without a journey")
        }
        val tool = checkNotNull(module.tools.firstOrNull { it.role == role }) { "${module.method} has no $role tool for ${outcome::class.simpleName}" }
        when (outcome) {
            is ToolOutcome.Failed.KnownAccountAuth -> accountLockoutService.recordFailure(accountId, ChannelType.WEB.name, tool.method, tool = null)
            is ToolOutcome.Completed.Authenticated -> {
                checkStaysWithin(tool, outcome)
                accountLockoutService.recordSuccess(accountId)
            }
            is ToolOutcome.Completed.Enrolled -> {
                checkStaysWithin(tool, outcome)
                assertClaimsCovered(tool, outcome.claims)
                // The claim naming the instance first, then the instance, as in a journey: without
                // the claim a later revocation would leave "has a password" standing.
                val instanceId = UUID.randomUUID()
                accountService.recordClaims(accountId, outcome.claims, provenAcr = AcrLevels.DEFAULT_REQUIRED_ACR, authMethodId = instanceId)
                accountService.addAuthenticationMethod(
                    accountId = accountId,
                    method = tool.method,
                    enrollmentRef = outcome.enrollmentRef,
                    enrolledUnderAcr = null,
                    boundKeyRef = outcome.boundKeyRef,
                    reference = outcome.reference,
                    allowsMultipleInstances = tool.allowsMultipleInstances,
                    instanceId = instanceId
                )
            }
            else -> Unit
        }
    }

}
