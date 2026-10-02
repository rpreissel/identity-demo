package com.example.identity.core.orchestrator.keycloak

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fetches and caches Keycloak's JWKS for peer-auth verification (ADR-7). An unknown `kid` triggers
 * a refetch, so key rotation needs no restart. At most once per [MIN_REFETCH_INTERVAL], so made-up
 * `kid`s cannot turn every request into a fetch.
 */
@Component
class KeycloakJwkSource(
    @Value("\${keycloak.peer-auth.jwks-uri}") private val jwksUri: String,
    @Value("\${keycloak.peer-auth.jwks-cache-ttl-seconds:600}") private val cacheTtlSeconds: Long,
    private val clock: Clock,
    // Only under the `keycloak` profile; without it the JVM defaults apply.
    private val keycloakHttp: KeycloakHttp? = null,
) {
    private val lock = ReentrantLock()
    private var cached: JWKSet? = null
    private var cachedAt: Instant = Instant.EPOCH

    /** Null for an unknown kid - and for every kid when no issuer is configured (blank jwks-uri, no `keycloak` profile). */
    fun find(kid: String): JWK? {
        if (jwksUri.isBlank()) return null
        val known = currentSet().getKeyByKeyId(kid)
        if (known != null) return known
        // Unknown kid: maybe a just-rotated key, worth one refetch unless the set is fresh.
        return refreshUnlessRecent().getKeyByKeyId(kid)
    }

    private fun currentSet(): JWKSet = lock.withLock {
        val existing = cached
        if (existing != null && clock.instant().isBefore(cachedAt.plusSeconds(cacheTtlSeconds))) {
            existing
        } else {
            fetchAndCache()
        }
    }

    private fun refreshUnlessRecent(): JWKSet = lock.withLock {
        val existing = cached
        if (existing != null && clock.instant().isBefore(cachedAt.plus(MIN_REFETCH_INTERVAL))) existing else fetchAndCache()
    }

    private fun fetchAndCache(): JWKSet {
        val fetched = keycloakHttp?.let { JWKSet.parse(it.getText(jwksUri)) }
            ?: JWKSet.load(URI.create(jwksUri).toURL(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, SIZE_LIMIT_BYTES)
        cached = fetched
        cachedAt = clock.instant()
        return fetched
    }

    private companion object {
        val MIN_REFETCH_INTERVAL: java.time.Duration = java.time.Duration.ofSeconds(30)
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 10_000
        const val SIZE_LIMIT_BYTES = 64 * 1024
    }
}
