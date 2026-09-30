package com.example.identity.tools.auth_sms

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import org.springframework.stereotype.Component

/** Shared by enroll-sms/auth-sms/auth-sms-lookup - the one place "sms" is spelled out. */
internal const val SMS_METHOD = "sms"

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-sms writes and the login tools read back. */
internal const val SMS_ENROLLMENT_TYPE = "auth_sms.enrollment"

/** Self-description for every auth_sms tool (docs/03-tool-architektur.md #1), one bean per toolId. */
@Component
object EnrollSmsDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-sms")
    override val role = ToolRole.ENROLLMENT
    override val method = SMS_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = AcrLevel.LOA1

    // A confirmed TAN proves the subject holds this number, so it goes into the claim log. No
    // anchor and no uniqueness: several accounts may share one number (a family phone), and
    // auth-sms-lookup never resolves by number.
    override val claims = setOf(ClaimDeclaration(AttributeType.PHONE_NUMBER, ClaimSource(toolId.value)))
}

@Component
object AuthSmsDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-sms")
    override val role = ToolRole.KNOWN_ACCOUNT_AUTH
    override val method = SMS_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = AcrLevel.LOA1
}

@Component
object AuthSmsLookupDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-sms-lookup")
    override val role = ToolRole.ACCOUNT_LOOKUP_AUTH
    override val method = SMS_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = AcrLevel.LOA1
}
