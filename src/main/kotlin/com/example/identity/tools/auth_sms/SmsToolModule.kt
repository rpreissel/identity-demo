package com.example.identity.tools.auth_sms

import com.example.identity.tools.auth_sms.api.v1.SmsStepData
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.FactorType.POSSESSION
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

/** Mobile number, established by an `enroll-sms` run - the address a TAN is delivered to. Owned by this module. */
internal val PHONE_NUMBER = AttributeType.ownedByMethod("phone_number")

internal const val ENROLL_SMS_TOOL_ID = "enroll-sms"
internal const val AUTH_SMS_TOOL_ID = "auth-sms"
internal const val AUTH_SMS_LOOKUP_TOOL_ID = "auth-sms-lookup"

/**
 * The SMS procedure: `enroll-sms`, `auth-sms`, `auth-sms-lookup` (docs/03-tool-architektur.md #1).
 * A confirmed TAN proves the subject holds this number, so enrolling puts it into the claim log. No
 * anchor and no uniqueness: several accounts may share one number (a family phone), and
 * `auth-sms-lookup` never resolves by number.
 */
internal val SmsModule = toolModule(
    method = "sms",
    name = Text("SMS"),
    proves = factors(POSSESSION, upTo = AcrLevel.LOA1),
    stepData = SmsStepData,
)

internal val EnrollSms = SmsModule.enroll(
    ENROLL_SMS_TOOL_ID,
    // 2: the consent comes with the number (ADR-51). 1 stays for apps that cannot show it.
    versions = setOf(1, 2),
    hint = Text("Code an eine Telefonnummer"),
    claims = setOf(PHONE_NUMBER),
    changeable = true,
)
internal val AuthSms = SmsModule.login(AUTH_SMS_TOOL_ID, versions = setOf(1), hint = Text("Code an die hinterlegte Telefonnummer"))
internal val AuthSmsLookup = SmsModule.lookupLogin(AUTH_SMS_LOOKUP_TOOL_ID, versions = setOf(1), hint = Text("E-Mail-Adresse + SMS-Code"))

/**
 * A method module talks to the orchestrator through tool_api only, never to account or another
 * method module (docs/03-tool-architektur.md #2). Its controllers (`auth_sms.api.v1`) reach the
 * orchestrator through `tool_api` alone (docs/04-orchestrierung.md #5).
 */
@ApplicationModule(id = "auth_sms", allowedDependencies = ["tool_api", "texts", "sms"])
@Configuration
internal class SmsToolModule {
    @Bean
    fun smsModule() = SmsModule
}
