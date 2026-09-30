package com.example.identity.tools.auth_qr

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import org.springframework.stereotype.Component
import java.time.Duration

/** Shared by enroll-qr/auth-qr/auth-qr-lookup/confirm-qr-login - the one place "qr" is spelled out. */
internal const val QR_METHOD = "qr"

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-qr writes - a pure opt-in marker, no secret. */
internal const val QR_OPTIN_ENROLLMENT_TYPE = "auth_qr.enrollment"

/** How long a pairing request stays open (docs/07-betrieb.md #5 - not further validated). */
internal val QR_LOGIN_TTL: Duration = Duration.ofMinutes(5)

/**
 * `enroll-qr`: a pure opt-in marker, no secret (docs/03-tool-architektur.md). Without it `auth-qr`
 * is not offered, and `confirm-qr-login` may not approve a pairing for the account.
 */
@Component
object EnrollQrDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-qr")
    override val role = ToolRole.ENROLLMENT
    override val method = QR_METHOD
    override val factorTypes = emptySet<FactorType>()
    override val maxAcr = AcrLevel.LOA1
}

/**
 * WEB side, account already known via the channel (step-up or re-auth). `factorTypes` declares
 * POSSESSION and KNOWLEDGE: the approving app must first pass its own loa2 check, so the proof
 * carries what the app already proved. That makes this tool MFA on its own, like `ident-eid`.
 */
@Component
object AuthQrDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-qr")
    override val role = ToolRole.KNOWN_ACCOUNT_AUTH
    override val method = QR_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA2
    // Waits on the APP side, not a form input - never the role's own "auth" default.
    override val startStep = "waitForApp"
}

/** WEB side, account unknown until the app's approval reveals it (passwordless). Factors as in [AuthQrDescriptor]. */
@Component
object AuthQrLookupDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-qr-lookup")
    override val role = ToolRole.ACCOUNT_LOOKUP_AUTH
    override val method = QR_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA2
    override val startStep = "waitForApp"
}

/** APP-side: approves or declines a pending `auth-qr`/`auth-qr-lookup` pairing. */
@Component
object ConfirmQrLoginDescriptor : ToolDescriptor {
    override val toolId = ToolId("confirm-qr-login")
    override val role = ToolRole.PEER_APPROVAL
    override val method = QR_METHOD
    override val factorTypes = emptySet<FactorType>()
    override val maxAcr = AcrLevel.LOA2
}
