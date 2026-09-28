package com.example.identity.tools.auth_email

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.TrustLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.ClaimSource
import org.springframework.stereotype.Component

/** Shared by confirm-email/enroll-email/auth-email/auth-email-lookup - the one place "email" is spelled out. */
internal const val EMAIL_METHOD = "email"

/**
 * Self-description for every auth_email tool (docs/03-tool-architektur.md #1), one bean per toolId.
 * Two tools rather than one (ADR-17): [ConfirmEmailDescriptor] establishes the address as account
 * infrastructure, [EnrollEmailDescriptor] turns it into a login method.
 */

/**
 * Proves the subject controls an address, and nothing else: the account keeps it as its EMAIL
 * anchor, no method appears. Hence [MethodRole.ATTESTATION] and no [factorTypes]: a confirmed
 * address is not a factor, and reporting no `amr` keeps it from raising the channel's assurance.
 */
@Component
object ConfirmEmailDescriptor : ToolDescriptor {
    override val toolId = ToolId("confirm-email")
    override val role = MethodRole.ATTESTATION
    override val method = EMAIL_METHOD
    override val factorTypes = emptySet<FactorType>()
    override val maxAcr = AcrLevel.LOA1
    // The code exchange itself is the proof, so this tool is the claim's source.
    override val claims = setOf(ClaimDeclaration(AttributeType.EMAIL, ClaimSource.of(toolId)))
}

/**
 * Turns an already confirmed address into an authentication method. One shot without a code:
 * [ConfirmEmailDescriptor] proved control already. Reports no `amr`, since nothing was proven in
 * this run, so it must not raise the channel's assurance.
 */
@Component
object EnrollEmailDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-email")
    override val role = MethodRole.ENROLLMENT
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
    override val requires = setOf(ClaimRequirement(AttributeType.EMAIL, TrustLevel.PROVEN))
    override val completesOnActivation = Text("Ihre bereits bestätigte E-Mail-Adresse wird sofort zum Anmeldeverfahren. Einen Code brauchen Sie dafür nicht.")
}

@Component
object AuthEmailDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-email")
    override val role = MethodRole.IDENTIFIED_AUTH
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
}

@Component
object AuthEmailLookupDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-email-lookup")
    override val role = MethodRole.LOOKUP_AUTH
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
}
