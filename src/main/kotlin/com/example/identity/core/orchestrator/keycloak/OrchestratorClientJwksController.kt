package com.example.identity.core.orchestrator.keycloak

import com.nimbusds.jose.jwk.JWKSet
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.example.identity.contract.tool_api.envelope.API_V1

/**
 * Die oeffentlichen Schluessel, mit denen sich der Orchestrator bei Keycloak als Client ausweist
 * ([OrchestratorClientAssertionSigner]), einer je Client. Jeder Keycloak-Client traegt seine
 * Adresse als `jwks.url`. Ohne Authentisierung: hier liegt nur oeffentliches Schluesselmaterial.
 * Ein Client, den dieser Knoten nicht vertritt, bekommt 404.
 */
@RestController
@RequestMapping(CLIENT_JWKS_PATH)
@Profile("keycloak")
@Tag(name = "Keycloak-Kanal", description = "Oeffentliche Schluessel der Client-Authentisierung des Orchestrators")
class OrchestratorClientJwksController(private val signer: OrchestratorClientAssertionSigner) {

    @GetMapping("{clientId}/.well-known/jwks.json")
    @Operation(summary = "Public Key, gegen den Keycloak die private_key_jwt-Assertion dieses Clients prueft")
    fun jwks(@PathVariable clientId: String): ResponseEntity<Map<String, Any>> =
        signer.publicKeyOf(clientId)
            ?.let { ResponseEntity.ok(JWKSet(it).toJSONObject()) }
            ?: ResponseEntity.notFound().build()
}

/**
 * [com.example.identity.core.orchestrator.ReadinessGateFilter] laesst diesen Pfad schon waehrend der
 * Keycloak-Migrationen durch: die Migration meldet sich selbst gegen dieses JWKS an.
 */
const val CLIENT_JWKS_PATH = "$API_V1/kc/client-jwks"
