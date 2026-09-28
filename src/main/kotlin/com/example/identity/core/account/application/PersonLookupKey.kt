package com.example.identity.core.account.application

import com.example.identity.core.account.domain.passportForm
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A search key and the id of the secret it was computed with. */
data class LookupKey(val keyId: String, val value: String)

/**
 * Secrets that wrote older change log entries: `keyId -> secret`, only for searching. A rotated-out
 * secret stays here until no entry carries its id any more.
 */
@ConfigurationProperties(prefix = "account.change-log")
data class PreviousLookupSecrets(val previousLookupSecrets: Map<String, String> = emptyMap())

/**
 * The change log's search key for a person (ADR-39): a keyed hash of name, first name and date of
 * birth. Keyed, because so little entropy makes a plain hash reversible by trying common names.
 * A search tries the current and every previous secret. Keys cannot be recomputed, since the names
 * are not stored, so an old secret stays until its last entry is deleted ([LookupKeyCoverageCheck]).
 */
@Component
class PersonLookupKey(
    @Value("\${account.change-log.lookup-secret}") secret: String,
    @Value("\${account.change-log.lookup-key-id:1}") private val currentKeyId: String,
    previous: PreviousLookupSecrets,
) {
    private val current: SecretKeySpec
    private val previous: Map<String, SecretKeySpec> =
        previous.previousLookupSecrets.mapValues { (_, value) -> SecretKeySpec(value.toByteArray(), ALGORITHM) }

    /**
     * Whether the secret is the one `application.yml` ships for the demo. Keys computed with a
     * publicly known secret are reversible by anyone; `ProductionModeCheck` refuses it outside demo mode.
     */
    val usesDemoSecret: Boolean = secret == DEMO_SECRET

    init {
        check(secret.isNotBlank()) { "account.change-log.lookup-secret (CHANGE_LOG_LOOKUP_SECRET) must not be empty" }
        current = SecretKeySpec(secret.toByteArray(), ALGORITHM)
        check(currentKeyId !in this.previous) { "account.change-log.lookup-key-id '$currentKeyId' is also listed among the previous secrets" }
    }

    /** Every key id a search can match - the current one and each previous one. */
    val knownKeyIds: Set<String> get() = previous.keys + currentKeyId

    /** The key to write. `null` unless all three are known: a partial key would match far too many people. */
    fun of(name: String?, vorname: String?, geburtsdatum: LocalDate?): LookupKey? =
        input(name, vorname, geburtsdatum)?.let { LookupKey(currentKeyId, hmac(current, it)) }

    /** The keys to search for: the same person under the current and every previous secret. */
    fun candidates(name: String?, vorname: String?, geburtsdatum: LocalDate?): List<String> {
        val input = input(name, vorname, geburtsdatum) ?: return emptyList()
        return (previous.values + current).map { hmac(it, input) }
    }

    private fun input(name: String?, vorname: String?, geburtsdatum: LocalDate?): String? {
        if (name.isNullOrBlank() || vorname.isNullOrBlank() || geburtsdatum == null) return null
        return listOf(passportForm(name), passportForm(vorname), geburtsdatum.toString()).joinToString("\u001F")
    }

    private fun hmac(key: SecretKeySpec, input: String): String =
        HexFormat.of().formatHex(Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(input.toByteArray()))

    internal companion object {
        const val ALGORITHM = "HmacSHA256"
        /** The default in `application.yml`; `PersonLookupKeyTest` keeps the two equal. */
        const val DEMO_SECRET = "demo-only-change-log-lookup-secret"
    }
}
