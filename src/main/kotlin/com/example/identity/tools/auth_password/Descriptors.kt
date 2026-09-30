package com.example.identity.tools.auth_password

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.ClaimTrust
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import org.springframework.stereotype.Component

/** Shared by all password tools; the literal lives on [PasswordCredentialPort.METHOD] for other modules. */
internal const val PASSWORD_METHOD = PasswordCredentialPort.METHOD

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-password writes and the login tools read back. */
internal const val PASSWORD_ENROLLMENT_TYPE = "auth_password.enrollment"

/** Self-description for every auth_password tool (docs/03-tool-architektur.md #1), one bean per toolId. */
@Component
object EnrollPasswordDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-password")
    override val role = ToolRole.ENROLLMENT
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
    // No identifier field: the account's confirmed email is the identifier, so this tool only
    // ever asks for the password itself - and is only offered once that email is proven.
    override val requires = setOf(ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN))

    /**
     * States that this account has a password, so another method can depend on it via `requires`
     * (ADR-24). Revoking the password retracts this claim, and whatever required it falls with it.
     */
    override val claims = setOf(ClaimDeclaration(AttributeType.PASSWORD_EXISTS, ClaimSource(toolId.value)))
}

@Component
object AuthPasswordDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-password")
    override val role = ToolRole.KNOWN_ACCOUNT_AUTH
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
}

@Component
object AuthPasswordLookupDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-password-lookup")
    override val role = ToolRole.ACCOUNT_LOOKUP_AUTH
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
}
