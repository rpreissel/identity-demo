package com.example.identity.core.orchestrator.keycloak

import com.example.identity.core.orchestrator.dpop.DpopReplayProtectionService
import com.example.identity.core.orchestrator.dpop.DpopValidationException
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.text.ParseException
import java.time.Clock
import java.time.temporal.ChronoUnit

/** A `logout_token` that passed every check: Keycloak ended session [sid]. */
data class LogoutToken(val sid: String, val audience: String)

/** Why a `logout_token` was refused. The message names the rule only, never a value from the token: it goes to the log. */
class LogoutTokenException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Verifies a `logout_token` of OIDC Back-Channel Logout 1.0, section 2.6: signed by the realm, for
 * one of our clients, with the logout event, a `sid` and no `nonce`. Single use via
 * [DpopReplayProtectionService] under a `bcl:` prefix, so a captured token cannot be replayed.
 */
@Component
class LogoutTokenValidator(
    private val jwkSource: KeycloakRealmJwkSource,
    private val replayProtectionService: DpopReplayProtectionService,
    private val clock: Clock,
    @Value("\${keycloak.backchannel-logout.issuer}") private val expectedIssuer: String,
    @Value("\${keycloak.backchannel-logout.audiences}") audiences: String,
    @Value("\${keycloak.backchannel-logout.max-clock-skew-seconds:30}") private val maxClockSkewSeconds: Long,
    @Value("\${keycloak.backchannel-logout.max-age-seconds:120}") private val maxAgeSeconds: Long,
) {
    private val expectedAudiences: Set<String> = audiences.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun validate(token: String?): LogoutToken {
        if (expectedIssuer.isBlank() || expectedAudiences.isEmpty()) throw LogoutTokenException("Back-channel logout is not configured")
        if (token.isNullOrBlank()) throw LogoutTokenException("Missing logout_token")
        val jwt = try {
            SignedJWT.parse(token)
        } catch (e: ParseException) {
            throw LogoutTokenException("Invalid logout_token format", e)
        }
        val header = jwt.header
        // Explicit type, so no ID or access token of the same realm key is read as a logout (RFC 8725 3.11).
        if (header.type != LOGOUT_TOKEN_TYPE) throw LogoutTokenException("logout_token has the wrong typ")
        if (header.algorithm !in SUPPORTED_ALGORITHMS) throw LogoutTokenException("logout_token has an unsupported algorithm")
        val kid = header.keyID ?: throw LogoutTokenException("logout_token is missing kid")
        val claims = try {
            jwt.jwtClaimsSet
        } catch (e: ParseException) {
            throw LogoutTokenException("Invalid logout_token claims", e)
        }
        verifySignature(jwt, kid)
        val (sid, audience) = checkedClaims(claims)
        try {
            replayProtectionService.validateAndStore("bcl:$kid", claims.jwtid, claims.issueTime.toInstant().plus(maxAgeSeconds + maxClockSkewSeconds, ChronoUnit.SECONDS))
        } catch (e: DpopValidationException) {
            throw LogoutTokenException("logout_token replay detected", e)
        }
        return LogoutToken(sid, audience)
    }

    private fun verifySignature(jwt: SignedJWT, kid: String) {
        val jwk = jwkSource.find(kid) ?: throw LogoutTokenException("logout_token names an unknown key")
        // The realm also publishes encryption keys; only a signing key may vouch for a logout.
        if (jwk.keyUse != null && jwk.keyUse != KeyUse.SIGNATURE) throw LogoutTokenException("logout_token names a key that is not for signatures")
        // A key published for one algorithm vouches only for that one (RFC 8725 3.1).
        if (jwk.algorithm != null && jwk.algorithm != jwt.header.algorithm) throw LogoutTokenException("logout_token algorithm does not match its key")
        val valid = try {
            when {
                jwk is RSAKey && jwt.header.algorithm in RSA_ALGORITHMS -> jwt.verify(RSASSAVerifier(jwk.toRSAPublicKey()))
                jwk is ECKey && jwt.header.algorithm in EC_ALGORITHMS -> jwt.verify(ECDSAVerifier(jwk.toECPublicKey()))
                else -> throw LogoutTokenException("logout_token algorithm does not fit its key type")
            }
        } catch (e: JOSEException) {
            throw LogoutTokenException("Failed to verify logout_token signature", e)
        }
        if (!valid) throw LogoutTokenException("Invalid logout_token signature")
    }

    private fun checkedClaims(claims: JWTClaimsSet): Pair<String, String> {
        if (claims.issuer != expectedIssuer) throw LogoutTokenException("logout_token has an unexpected issuer")
        val audience = claims.audience.orEmpty().singleOrNull { it in expectedAudiences }
            ?: throw LogoutTokenException("logout_token has an unexpected audience")
        val now = clock.instant()
        val issuedAt = claims.issueTime?.toInstant() ?: throw LogoutTokenException("logout_token iat claim is missing")
        if (issuedAt.isAfter(now.plus(maxClockSkewSeconds, ChronoUnit.SECONDS))) throw LogoutTokenException("logout_token iat claim is in the future")
        if (issuedAt.isBefore(now.minus(maxAgeSeconds + maxClockSkewSeconds, ChronoUnit.SECONDS))) throw LogoutTokenException("logout_token iat claim is too old")
        val expiresAt = claims.expirationTime?.toInstant() ?: throw LogoutTokenException("logout_token exp claim is missing")
        if (expiresAt.isBefore(now.minus(maxClockSkewSeconds, ChronoUnit.SECONDS))) throw LogoutTokenException("logout_token has expired")
        if (claims.jwtid.isNullOrBlank()) throw LogoutTokenException("logout_token jti claim is missing")
        val events = try {
            claims.getJSONObjectClaim("events")
        } catch (e: ParseException) {
            throw LogoutTokenException("Invalid logout_token events claim", e)
        }
        if (events == null || BACKCHANNEL_LOGOUT_EVENT !in events) throw LogoutTokenException("logout_token is missing the back-channel logout event")
        // A nonce would make it an ID token (section 2.4).
        if (claims.getClaim("nonce") != null) throw LogoutTokenException("logout_token must not contain a nonce")
        // The orchestrator ends one session, never every session of a user: without sid there is nothing to end.
        val sid = claims.getClaim("sid") as? String
        if (sid.isNullOrBlank()) throw LogoutTokenException("logout_token is missing sid")
        return sid to audience
    }

    companion object {
        val LOGOUT_TOKEN_TYPE = JOSEObjectType("logout+jwt")
        const val BACKCHANNEL_LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout"
        private val RSA_ALGORITHMS = setOf(JWSAlgorithm.RS256, JWSAlgorithm.PS256)
        private val EC_ALGORITHMS = setOf(JWSAlgorithm.ES256)
        private val SUPPORTED_ALGORITHMS = RSA_ALGORITHMS + EC_ALGORITHMS
    }
}
