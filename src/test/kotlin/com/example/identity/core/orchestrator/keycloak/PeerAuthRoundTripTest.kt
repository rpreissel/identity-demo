package com.example.identity.core.orchestrator.keycloak

import com.example.identity.TEST_NOW
import com.example.identity.TEST_CLOCK
import com.example.identity.core.orchestrator.dpop.DpopReplayProtectionService
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.Date
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A signing key that exists only in the test source set - production has no key issuer of its own. */
private val TEST_PEER_AUTH_KEY: ECKey = ECKeyGenerator(Curve.P_256)
    .keyID("test-peer-auth-1")
    .algorithm(JWSAlgorithm.ES256)
    .keyUse(KeyUse.SIGNATURE)
    .generate()

/** Serves [TEST_PEER_AUTH_KEY]'s public half the way the real realm serves its orchestrator-jwks. */
@TestConfiguration
class TestPeerAuthJwksConfig {
    @RestController
    class TestPeerAuthJwksController {
        @GetMapping("/test-peer-auth/jwks.json")
        fun jwks(): Map<String, Any> = JWKSet(TEST_PEER_AUTH_KEY.toPublicJWK()).toJSONObject()
    }
}

/**
 * Proves the sign -> fetch -> verify round trip the Keycloak extension depends on: a real assertion
 * through a [PeerAuthValidator]/[KeycloakJwkSource] pair pointed at this server's JWKS endpoint
 * ([com.example.identity.core.orchestrator.KeycloakChannelIntegrationTest] mocks the validator). Built directly, not
 * autowired: `keycloak.peer-auth` has no issuer outside the `keycloak` profile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPeerAuthJwksConfig::class)
class PeerAuthRoundTripTest : BehaviorSpec() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var replayProtectionService: DpopReplayProtectionService

    private fun sign(key: ECKey, htm: String, htu: String, channelBinding: String, jti: String): String {
        val claims = JWTClaimsSet.Builder()
            .issuer("test-issuer")
            .audience("identity-demo-orchestrator")
            .claim("htm", htm)
            .claim("htu", htu)
            .claim("channel_binding", channelBinding)
            .jwtID(jti)
            .issueTime(Date.from(TEST_NOW))
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(PeerAuthValidator.ASSERTION_TYPE).keyID(key.keyID).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    init {
        given("a peer-auth assertion signed with a key the JWKS endpoint publishes") {
            `when`("a PeerAuthValidator pointed at that jwks.json validates it") {
                val jwkSource = KeycloakJwkSource(
                    jwksUri = "http://localhost:$port/test-peer-auth/jwks.json",
                    cacheTtlSeconds = 600,
                    clock = TEST_CLOCK
                )
                val validator = PeerAuthValidator(
                    jwkSource = jwkSource,
                    replayProtectionService = replayProtectionService,
                    expectedIssuer = "test-issuer",
                    expectedAudience = "identity-demo-orchestrator",
                    maxClockSkewSeconds = 30,
                    maxAssertionAgeSeconds = 30,
                    clock = TEST_CLOCK
                )
                val htu = "http://localhost:$port/orchestrator/api/v1/kc/channels/${UUID.randomUUID()}"
                val binding = "channel-binding-${UUID.randomUUID()}"
                val jti = UUID.randomUUID().toString()
                val token = sign(TEST_PEER_AUTH_KEY, "PATCH", htu, binding, jti)

                val assertion = validator.validate(token, "PATCH", htu)

                then("it verifies via the real sign -> fetch -> verify round trip and hands on what was signed") {
                    assertion.channelBinding shouldBe binding
                    assertion.jti shouldBe jti
                }
            }
        }
    }
}
