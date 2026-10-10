package com.example.identity.tools.auth_sms.internal

import com.example.identity.contract.tool_api.otp.OneTimeCodes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock

/** The TANs of the sms tools ([OneTimeCodes]). */
@Component
class TanGenerator(@Value("\${identity.secrets.otp-pepper:}") configuredPepper: String, clock: Clock) : OneTimeCodes(configuredPepper, clock)
