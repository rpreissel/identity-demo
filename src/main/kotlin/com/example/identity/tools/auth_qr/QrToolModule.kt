package com.example.identity.tools.auth_qr

import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_qr.api.v1.QrStepData
import com.example.identity.contract.tool_api.FactorType.KNOWLEDGE
import com.example.identity.contract.tool_api.FactorType.POSSESSION
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

internal const val ENROLL_QR_TOOL_ID = "enroll-qr"
internal const val AUTH_QR_TOOL_ID = "auth-qr"
internal const val AUTH_QR_LOOKUP_TOOL_ID = "auth-qr-lookup"
internal const val APPROVE_QR_TOOL_ID = "approve-qr"

/**
 * QR-Login (docs/03-tool-architektur.md #1). `enroll-qr` is a pure opt-in marker without secret:
 * without it `auth-qr` is not offered and `approve-qr` may not approve a pairing for the account.
 * `auth-qr` (account known) and `auth-qr-lookup` (account unknown until the app's approval reveals
 * it) wait on the APP side; the approving app must first pass its own loa2 check, so the proof
 * carries what the app already proved: POSSESSION and KNOWLEDGE, MFA on its own like `ident-eid`.
 * `approve-qr` decides a pending pairing from the authenticated APP channel.
 */
internal val QrModule = toolModule(
    method = "qr",
    name = Text("QR-Login"),
    proves = factors(POSSESSION, KNOWLEDGE, upTo = AcrLevel.LOA2),
    stepData = QrStepData,
)

internal val EnrollQr = QrModule.enroll(
    ENROLL_QR_TOOL_ID,
    hint = Text("Web-Login per QR-Code erlauben"),
    optInOnly = true,
)
internal val AuthQr = QrModule.login(
    AUTH_QR_TOOL_ID,
    hint = Text("QR-Code mit der App scannen oder Code manuell in der App eingeben"),
    name = Text("Mit App bestätigen"),
    startStep = "waitForApp",
)
internal val AuthQrLookup = QrModule.lookupLogin(
    AUTH_QR_LOOKUP_TOOL_ID,
    hint = Text("QR-Code mit einer bereits angemeldeten App scannen oder Code manuell eingeben"),
    name = Text("Mit App anmelden"),
    startStep = "waitForApp",
)
internal val ApproveQr = QrModule.approve(APPROVE_QR_TOOL_ID, hint = Text("Web-Login per QR bestätigen"))

/** How long a pairing request stays open (docs/07-betrieb.md #5 - not further validated). */
internal val QR_LOGIN_TTL: Duration = Duration.ofMinutes(5)

/**
 * QR-Login: a WEB channel shows a pairing/verification code (`auth-qr`/`auth-qr-lookup`), an
 * already-authenticated APP channel approves or declines it (`approve-qr`), both sides of the same
 * `qr` procedure living in one module like every other pair (docs/08-projektrahmen.md M11). Talks
 * to the orchestrator through `tool_api` only, exactly like every other method module
 * (docs/03-tool-architektur.md #2).
 */
@ApplicationModule(id = "auth_qr", allowedDependencies = ["tool_api", "texts"])
@Configuration
internal class QrToolModule {
    @Bean
    fun qrModule() = QrModule
}
