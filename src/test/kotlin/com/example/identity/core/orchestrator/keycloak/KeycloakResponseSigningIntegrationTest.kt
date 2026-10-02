package com.example.identity.core.orchestrator.keycloak

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.IntegrationTestSupport
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Every answer to a peer-auth request carries the orchestrator's signature over exactly this status and body,
 * bound to the request's jti - checkable against the published JWKS.
 */
class KeycloakResponseSigningIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    /** A peer-auth assertion as Keycloak sends it, signed with a throwaway key: [peerAuthValidator] is stubbed. */
    private fun peerAuthAssertion(jti: String, channelBinding: String): String =
        SignedJWT(
            JWSHeader(JWSAlgorithm.ES256),
            JWTClaimsSet.Builder().issuer("kc-test").audience("orch-test").jwtID(jti)
                .issueTime(Date()).claim("channel_binding", channelBinding).build()
        ).apply { sign(ECDSASigner(ECKeyGenerator(Curve.P_256).generate())) }.serialize()

    private fun sha256(body: String): String =
        Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(body.toByteArray())).toString()

    init {
        given("a peer-auth request from Keycloak") {
            `when`("the orchestrator answers it") {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                val jti = UUID.randomUUID().toString()
                every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
                    jti = jti, issuedAt = Instant.now(), channelBinding = channelSessionId.toString(), subject = null
                )
                val assertion = peerAuthAssertion(jti, channelSessionId.toString())

                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId",
                    HttpMethod.PATCH,
                    HttpEntity(withDefaultAvailableTools("{}"), HttpHeaders().apply {
                        set("Authorization", "Bearer $assertion")
                        set("Content-Type", "application/json")
                    }),
                    String::class.java
                )
                val jwks = restTemplate.getForObject("http://localhost:$port$RESPONSE_JWKS_PATH", String::class.java)

                then("the answer is signed for exactly this request, status and body") {
                    val signature = response.headers.getFirst(KeycloakResponseSigner.HEADER)
                    signature shouldNotBe null
                    val jwt = SignedJWT.parse(signature)
                    val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as ECKey
                    jwt.verify(ECDSAVerifier(key)) shouldBe true

                    val claims = jwt.jwtClaimsSet
                    claims.getStringClaim("req") shouldBe jti
                    claims.issuer shouldBe "orch-test"
                    claims.audience shouldBe listOf("kc-test")
                    (claims.getClaim("status") as Number).toInt() shouldBe response.statusCode.value()
                    claims.getStringClaim("body_sha256") shouldBe sha256(response.body!!)
                }
            }
        }

        given("Keycloak asking for the text bundle with a peer-auth assertion") {
            `when`("the orchestrator answers it") {
                val jti = UUID.randomUUID().toString()
                val assertion = peerAuthAssertion(jti, "texts")

                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/texts/de", HttpMethod.GET,
                    HttpEntity<Void>(HttpHeaders().apply { set("Authorization", "Bearer $assertion") }), String::class.java
                )

                then("the wordings come signed too, so nobody on the hop decides what the login page says") {
                    val jwt = SignedJWT.parse(response.headers.getFirst(KeycloakResponseSigner.HEADER))
                    jwt.jwtClaimsSet.getStringClaim("req") shouldBe jti
                    jwt.jwtClaimsSet.getStringClaim("body_sha256") shouldBe sha256(response.body!!)
                }
            }
        }

        given("an ordinary App request (DPoP, no peer-auth assertion)") {
            `when`("the orchestrator answers it") {
                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST,
                    HttpEntity(withDefaultAvailableTools("{}"), headers()), String::class.java
                )

                then("nothing is signed") {
                    response.headers.getFirst(KeycloakResponseSigner.HEADER) shouldBe null
                }
            }
        }
    }
}
