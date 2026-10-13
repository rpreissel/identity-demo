package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.keycloak.LogoutTokenValidator
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Stands in for the realm's signing key in tests: `logout_token`s shaped like Keycloak's (see the
 * spike in docs/ideen/logout-ueber-keycloak.md Abschnitt 7). Issuer and audiences are those of
 * application-test.yml.
 */
object LogoutTokens {
    const val KID = "test-realm-key"
    const val ISSUER = "https://kc.test/realms/Demo"
    const val WEB_CLIENT = "identity-demo-web"
    const val APP_CLIENT = "orchestrator-app-token"

    private val key: RSAKey = RSAKeyGenerator(2048).keyID(KID).keyUse(KeyUse.SIGNATURE).generate()
    val publicKey: RSAKey = key.toPublicJWK()

    /** A valid token for [sid]; [customize] changes header or claims for the cases that must fail. */
    fun signed(
        sid: String?,
        audience: String = WEB_CLIENT,
        signingKey: RSAKey = key,
        now: Instant = Instant.now(),
        header: JWSHeader.Builder.() -> Unit = {},
        customize: JWTClaimsSet.Builder.() -> Unit = {},
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(audience)
            .subject("f:component:42")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .jwtID(UUID.randomUUID().toString())
            .claim("typ", "Logout")
            .claim("events", mapOf(LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT to emptyMap<String, Any>()))
            .apply { if (sid != null) claim("sid", sid) }
            .apply(customize)
            .build()
        val jwsHeader = JWSHeader.Builder(JWSAlgorithm.RS256).type(LogoutTokenValidator.LOGOUT_TOKEN_TYPE).keyID(KID).apply(header).build()
        return SignedJWT(jwsHeader, claims).apply { sign(RSASSASigner(signingKey)) }.serialize()
    }

    /** A key the realm never published, for the forged-signature case. */
    fun foreignKey(): RSAKey = RSAKeyGenerator(2048).keyID(KID).generate()
}
