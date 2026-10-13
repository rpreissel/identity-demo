package com.example.identity.core.orchestrator.keycloak

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A JWKS from Keycloak, fetched and cached. An unknown `kid` triggers a refetch, so key rotation
 * needs no restart. At most once per [MIN_REFETCH_INTERVAL], so made-up `kid`s cannot turn every
 * request into a fetch. [unavailable] builds the caller's own exception when there is no set at all.
 */
class CachedJwks(
    private val jwksUri: String,
    private val cacheTtlSeconds: Long,
    private val clock: Clock,
    private val keycloakHttp: KeycloakHttp?,
    private val unavailable: (String, Exception?) -> RuntimeException,
) {
    private val lock = ReentrantLock()
    private var cached: JWKSet? = null
    private var cachedAt: Instant = Instant.EPOCH
    private var lastFetchAt: Instant = Instant.EPOCH

    /** Null for an unknown kid, and for every kid when no address is configured. */
    fun find(kid: String): JWK? {
        if (jwksUri.isBlank()) return null
        val known = currentSet().getKeyByKeyId(kid)
        if (known != null) return known
        // Unknown kid: maybe a just-rotated key, worth one refetch unless the set is fresh.
        return refreshUnlessRecent().getKeyByKeyId(kid)
    }

    private fun currentSet(): JWKSet = lock.withLock {
        val existing = cached
        if (existing != null && clock.instant().isBefore(cachedAt.plusSeconds(cacheTtlSeconds))) existing else fetchAndCache()
    }

    private fun refreshUnlessRecent(): JWKSet = lock.withLock { fetchAndCache() }

    /**
     * At most one fetch per [MIN_REFETCH_INTERVAL], failed ones included: while Keycloak does not
     * answer, requests do not queue up behind the lock for a fetch each. A failed fetch keeps the
     * last good set; without one, the token is rejected.
     */
    private fun fetchAndCache(): JWKSet {
        val existing = cached
        if (clock.instant().isBefore(lastFetchAt.plus(MIN_REFETCH_INTERVAL))) {
            return existing ?: throw unavailable("Keycloak JWKS unavailable", null)
        }
        lastFetchAt = clock.instant()
        val fetched = try {
            keycloakHttp?.let { JWKSet.parse(it.getText(jwksUri, SIZE_LIMIT_BYTES)) }
                ?: JWKSet.load(URI.create(jwksUri).toURL(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, SIZE_LIMIT_BYTES)
        } catch (e: Exception) {
            return existing ?: throw unavailable("Keycloak JWKS fetch failed", e)
        }
        cached = fetched
        cachedAt = clock.instant()
        return fetched
    }

    private companion object {
        val MIN_REFETCH_INTERVAL: Duration = Duration.ofSeconds(30)
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 10_000
        const val SIZE_LIMIT_BYTES = 64 * 1024
    }
}
