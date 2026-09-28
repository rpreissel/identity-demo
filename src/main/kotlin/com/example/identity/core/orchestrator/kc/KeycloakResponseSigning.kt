package com.example.identity.core.orchestrator.kc

import com.example.identity.contract.tool_api.envelope.API_V1
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.time.Clock
import java.util.Date
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * Signiert jede Antwort an Keycloak. Die Antwort entscheidet, wer eingeloggt wird; ohne Signatur
 * bestimmte das, wer auf dem Hop antworten kann. TLS ist Umgebung (ADR-35), die Echtheit Kern.
 * Der JWS in [HEADER] bindet `req` (die `jti` der Anfrage), `status` und `body_sha256`, dazu
 * `iss`/`aud` spiegelbildlich zur Anfrage. Eigener Schluessel ([PURPOSE]), ein Schluessel je Zweck.
 */
@Component
class KeycloakResponseSigner(repository: NodeSigningKeyRepository, private val clock: Clock) {
    private val nodeKeys = NodeKeys(repository, clock)

    private fun key(): ECKey = nodeKeys.keyFor(PURPOSE, KEY_ID_PREFIX)

    fun publicKey(): ECKey = key().toPublicJWK()

    fun sign(request: JWTClaimsSet, status: Int, body: ByteArray): String {
        val key = key()
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .issuer(request.audience.singleOrNull())
            .audience(request.issuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(TTL_SECONDS)))
            .claim("req", request.jwtid)
            .claim("status", status)
            .claim("body_sha256", Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(body)).toString())
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType(TYPE)).build(), claims)
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    companion object {
        const val HEADER = "Orchestrator-Response-Signature"
        const val TYPE = "orchestrator-response+jwt"
        const val PURPOSE = "keycloak-response"
        private const val KEY_ID_PREFIX = "orchestrator-response"
        private const val TTL_SECONDS = 60L
    }
}

/**
 * Haengt die Signatur an jede Antwort auf eine Peer-Auth-Anfrage. Erkannt an der Assertion selbst,
 * nicht an einer Pfadliste, damit kein neuer Endpunkt sie vergisst.
 */
@Component
class KeycloakResponseSigningFilter(private val signer: KeycloakResponseSigner) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = peerAuthClaims(request) == null

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val claims = checkNotNull(peerAuthClaims(request))
        val wrapped = ContentCachingResponseWrapper(response)
        try {
            chain.doFilter(request, wrapped)
        } finally {
            wrapped.setHeader(KeycloakResponseSigner.HEADER, signer.sign(claims, wrapped.status, wrapped.contentAsByteArray))
            wrapped.copyBodyToResponse()
        }
    }

    /** Die Claims einer Peer-Auth-Assertion, sonst `null`. Nur gelesen; geprueft wird am Endpunkt. */
    private fun peerAuthClaims(request: HttpServletRequest): JWTClaimsSet? {
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim() ?: return null
        val claims = runCatching { SignedJWT.parse(token).jwtClaimsSet }.getOrNull() ?: return null
        return claims.takeIf { it.getClaim("channel_anchor") != null && it.jwtid != null }
    }
}

@RestController
@Tag(name = "Keycloak-Kanal", description = "Oeffentlicher Schluessel, gegen den Keycloak die Antworten des Orchestrators prueft")
class KeycloakResponseJwksController(private val signer: KeycloakResponseSigner) {

    @GetMapping(RESPONSE_JWKS_PATH)
    @Operation(operationId = "keycloakResponseJwks", summary = "Public Key der Antwortsignatur (Header Orchestrator-Response-Signature)")
    fun jwks(): Map<String, Any> = JWKSet(signer.publicKey()).toJSONObject()
}

const val RESPONSE_JWKS_PATH = "$API_V1/kc/response-jwks/.well-known/jwks.json"
