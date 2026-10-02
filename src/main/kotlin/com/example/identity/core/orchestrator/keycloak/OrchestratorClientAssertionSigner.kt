package com.example.identity.core.orchestrator.keycloak

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.util.Date
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Weist den Orchestrator bei Keycloak per signierter Assertion (`private_key_jwt`, RFC 7523) aus,
 * statt per `client_secret`: Signatur statt Secret in beiden Richtungen (ADR-7, ADR-9). Jeder
 * Client hat ein eigenes Schluesselpaar, weil die Clients sehr verschiedene Rechte haben; ein
 * gemeinsamer Schluessel machte die Rechtetrennung wertlos. Die Paare liegen in [NodeKeys].
 */
@Component
@Profile("keycloak")
class OrchestratorClientAssertionSigner(
    private val keys: NodeSigningKeyRepository,
    @Value("\${keycloak-sync.admin-client-id}") adminClientId: String,
    @Value("\${keycloak-sync.app-client-id}") appClientId: String,
    private val clock: Clock,
) {
    /** Die Clients, fuer die dieser Knoten signiert - jeder mit eigenem Schluessel. */
    val clientIds: Set<String> = setOf(adminClientId, appClientId, KeycloakMigrationToken.CLIENT_ID)

    private val nodeKeys = NodeKeys(keys, clock)

    /** Oeffentlicher Schluessel von [clientId], oder `null` fuer einen Client, den dieser Knoten nicht vertritt. */
    fun publicKeyOf(clientId: String): ECKey? =
        if (clientId in clientIds) keyOf(clientId).toPublicJWK() else null

    private fun keyOf(clientId: String): ECKey {
        check(clientId in clientIds) { "Fuer Client '$clientId' signiert dieser Orchestrator nicht" }
        return nodeKeys.keyFor(NodeSigningKey.keycloakClientAuth(clientId), clientId)
    }

    /**
     * [audience] ist die oeffentliche Realm-Adresse (KC_HOSTNAME), aus der Keycloak die erwartete
     * Audience bildet. Genau eine, weil Keycloak mehrere Audiences ablehnt.
     */
    fun assertionFor(clientId: String, audience: String): String {
        val key = keyOf(clientId)
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .issuer(clientId)
            .subject(clientId)
            .audience(audience)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ASSERTION_TTL_SECONDS)))
            .build()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build(),
            claims,
        )
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    private companion object {
        /** Kurzlebig wie jede andere Assertion in diesem Projekt - sie wird sofort eingeloest. */
        const val ASSERTION_TTL_SECONDS = 60L
    }
}
