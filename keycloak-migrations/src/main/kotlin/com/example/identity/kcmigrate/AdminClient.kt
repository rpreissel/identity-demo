package com.example.identity.kcmigrate

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.ext.ContextResolver
import jakarta.ws.rs.ext.Provider
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder

/**
 * keycloak-admin-client 26.0.12 bringt ein eigenes Representation-Modell mit Feldern mit, die der
 * Server nicht kennt (z.B. "maxSecondaryAuthFailures"). Als null im PUT-Body lehnt der Server sie mit
 * 400 "Unrecognized field" ab. NON_NULL lässt sie weg; FAIL_ON_UNKNOWN_PROPERTIES=false deckt die
 * Gegenrichtung ab.
 */
@Provider
class LenientJacksonResolver : ContextResolver<ObjectMapper> {
    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
    }

    override fun getContext(type: Class<*>?): ObjectMapper = mapper
}

/**
 * Setzt bei jedem Request ein frisches Bearer-Token aus [accessToken]. Der Admin-Client kann sich
 * nicht per `private_key_jwt` anmelden, und Master-Realm-Tokens leben nur 60 Sekunden, ein fester
 * Wert liefe mitten in der Migration ab. Die Prioritaet liegt hinter dem BearerAuthFilter des
 * Admin-Clients, dieser Filter hat also das letzte Wort.
 */
@Priority(Priorities.USER + 100)
private class FreshBearerToken(private val accessToken: () -> String) : ClientRequestFilter {
    override fun filter(requestContext: ClientRequestContext) {
        requestContext.headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken()}")
    }
}

/**
 * [accessToken] liefert ein gueltiges Master-Realm-Token (der Aufrufer cacht und erneuert es).
 * insecure=true, weil der Compose-Stack Keycloak über ein selbstsigniertes Zertifikat anspricht.
 */
fun buildAdminClient(url: String, accessToken: () -> String, insecure: Boolean): Keycloak {
    val clientBuilder = ResteasyClientBuilder.newBuilder() as ResteasyClientBuilder
    if (insecure) clientBuilder.disableTrustManager()
    val resteasyClient = clientBuilder
        .register(LenientJacksonResolver())
        .register(FreshBearerToken(accessToken))
        .build()

    return KeycloakBuilder.builder()
        .serverUrl(url)
        .realm("master")
        // Platzhalter: ein gesetztes authorization haelt den Admin-Client davon ab, sich selbst
        // anmelden zu wollen. Das tatsaechliche Token setzt FreshBearerToken.
        .authorization(TOKEN_PLACEHOLDER)
        .resteasyClient(resteasyClient)
        .build()
}

private const val TOKEN_PLACEHOLDER = "set-per-request"
