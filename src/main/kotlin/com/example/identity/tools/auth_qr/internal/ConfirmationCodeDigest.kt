package com.example.identity.tools.auth_qr.internal

import com.example.identity.contract.tool_api.otp.OneTimeCodes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * The stored form of the six-digit confirmation code in `login_request` ([OneTimeCodes.digest]).
 * The code itself comes from the app, so this module issues none.
 */
@Component
class ConfirmationCodeDigest(@Value("\${identity.secrets.otp-pepper:}") configuredPepper: String, clock: Clock) : OneTimeCodes(configuredPepper, clock)
