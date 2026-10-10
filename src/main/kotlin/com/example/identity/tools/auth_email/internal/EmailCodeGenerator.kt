package com.example.identity.tools.auth_email.internal

import com.example.identity.contract.tool_api.otp.OneTimeCodes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock

/** The confirmation codes of the email tools ([OneTimeCodes]). */
@Component
class EmailCodeGenerator(@Value("\${identity.secrets.otp-pepper:}") configuredPepper: String, clock: Clock) : OneTimeCodes(configuredPepper, clock)
