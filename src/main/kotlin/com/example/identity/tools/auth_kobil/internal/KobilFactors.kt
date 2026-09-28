package com.example.identity.tools.auth_kobil.internal

import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.FactorType

/**
 * What one KOBIL run proves. POSSESSION rests on an assertion redeemed from KOBIL itself. How the
 * user unlocked the local secret no server can see; it counts anyway, with the same reservation as
 * `auth_device`. One function for both tools, so enrollment and login price the same act alike.
 */
internal fun UserVerification.kobilFactorTypes(): Set<FactorType> = when (this) {
    UserVerification.PIN -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    UserVerification.BIOMETRIC -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
}
