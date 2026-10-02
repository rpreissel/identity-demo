package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.example.identity.core.orchestrator.domain.AmrSource
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.Duration
import java.util.Date

/**
 * Signs and verifies [RestoreData] as a compact JWT (docs/05-api.md Abschnitt 3). Keycloak only
 * stores an opaque token it cannot forge. `sub` is the `kcSessionId` it was minted for, and
 * [decode] refuses any other session. HS256 with a key of its own, not shared with peer-auth, and
 * fresh per boot: a restart invalidates tokens in flight, acceptable for a one-session value.
 */
@Component
class RestoreDataCodec(private val clock: Clock, private val ttl: Duration = TTL) {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun encode(restoreData: RestoreData, kcSessionId: String): String {
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .subject(kcSessionId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .claim("accountId", restoreData.accountId?.value)
            .claim("methods", restoreData.evidence?.methods?.map { it.toClaim() })
            .build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        jwt.sign(MACSigner(secret))
        return jwt.serialize()
    }

    /**
     * `null` on any problem (signature, expiry, other `kcSessionId`), never thrown. A bad token
     * means "start fresh": it only narrows what is restored, never what the caller may do.
     */
    fun decode(token: String, kcSessionId: String?): RestoreData? {
        if (kcSessionId == null) return null
        return try {
            val jwt = SignedJWT.parse(token)
            if (!jwt.verify(MACVerifier(secret))) return null
            val claims = jwt.jwtClaimsSet
            if (claims.subject != kcSessionId) return null
            if (claims.expirationTime?.before(Date.from(clock.instant())) != false) return null
            @Suppress("UNCHECKED_CAST")
            val methodsClaim = claims.getClaim("methods") as? List<Map<String, Any?>>
            val methods = methodsClaim?.map { it.toMethodEvidence() }
            RestoreData(
                accountId = (claims.getClaim("accountId") as? Number)?.toLong()?.let(::AccountId),
                evidence = methods?.takeIf { it.isNotEmpty() }?.let { SessionEvidence(it) }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun MethodEvidence.toClaim(): Map<String, Any?> = buildMap {
        put("method", method.value)
        put("loa", loa.value)
        enrolledUnderAcr?.let { put("enrolledUnderAcr", it.value) }
        if (factorTypes.isNotEmpty()) put("factorTypes", factorTypes.map { it.name })
        // Both carried verbatim: `source` keeps a restored method's orchestrator strength instead
        // of degrading it to a Keycloak self-report.
        put("source", source)
        put("amrSourceId", amrSourceId)
        // Restoring a proof must not make it young again (docs/04-orchestrierung.md #8).
        provenAt?.let { put("provenAt", it.epochSecond) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toMethodEvidence(): MethodEvidence = MethodEvidence(
        method = MethodName(this["method"] as String),
            loa = AcrLevel.parse(this["loa"] as String) ?: AcrLevel.NONE,
            enrolledUnderAcr = (this["enrolledUnderAcr"] as? String)?.let(AcrLevel::parse),
        factorTypes = (this["factorTypes"] as? List<String>)?.mapNotNull { name ->
            runCatching { FactorType.valueOf(name) }.getOrNull()
        }?.toSet() ?: emptySet(),
        source = this["source"] as? String ?: AmrSource.KEYCLOAK,
        amrSourceId = this["amrSourceId"] as? String ?: this["method"] as String,
        // Without a time the proof is of unknown age, never a fresh one.
        provenAt = (this["provenAt"] as? Number)?.let { Instant.ofEpochSecond(it.toLong()) } ?: Instant.EPOCH,
    )

    companion object {
        // Longer than the Web channel TTL: a Keycloak UserSession can outlive many channels, and
        // this validity is what bounds a restore.
        private val TTL: Duration = Duration.ofHours(12)
    }
}
