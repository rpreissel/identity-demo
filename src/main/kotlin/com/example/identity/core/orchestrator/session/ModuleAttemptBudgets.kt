package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.budget.AttemptBudget
import com.example.identity.contract.tool_api.budget.AttemptBudgets
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The [AttemptBudgets] port: the tool modules' budgets on [AttemptCounter], one scope per
 * namespace (ADR-44). Only the mechanism lives here; limits, keys and resets are the module's.
 */
@Service
@Transactional
class ModuleAttemptBudgets(
    private val counter: AttemptCounter,
    @Value("\${identity.secrets.otp-pepper:}") configuredPepper: String
) : AttemptBudgets {

    // Reuses identity.secrets.otp-pepper: a phone number or address has too little entropy to resist
    // an offline dictionary pass on its own. Blank means a random pepper per boot; a reset on
    // restart is harmless for a budget of minutes.
    private val pepper: ByteArray = configuredPepper.takeIf { it.isNotBlank() }?.toByteArray()
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    override fun tryAttempt(budget: AttemptBudget, key: String): Boolean =
        counter.recordWindowedAttempt(budget.namespace, hash(key), budget.maxPerWindow, budget.window)

    override fun reset(budget: AttemptBudget, key: String) = counter.reset(budget.namespace, hash(key))

    // HMAC under the pepper, not a bare digest: a key is often guessable PII, and the subject only
    // needs to prove "same key as before". Also fits the 128-char column.
    private fun hash(key: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pepper, HMAC_ALGORITHM))
        val digest = mac.doFinal(key.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
