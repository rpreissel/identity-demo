package com.example.identity.core.orchestrator.keycloak

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.dpop.DpopReplayProtectionService
import com.example.identity.core.orchestrator.dpop.inMemoryReplayRepository
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Date
import java.util.UUID

/**
 * The orchestrator signs only answers to assertions Keycloak really sent: with the real
 * [PeerAuthValidator], not a stub, behind the body capture as in the running application. A forged
 * assertion or a swapped body gets an unsigned answer, so nobody obtains a signed one (ADR-7).
 */
class KeycloakResponseSigningFilterTest : BehaviorSpec({

    val keycloakKey = ECKeyGenerator(Curve.P_256).keyID("kc").generate()
    val path = "/orchestrator/api/v1/kc/channels/abc"
    val htu = "http://localhost$path"
    val body = """{"targetAcr":"loa1"}"""

    val validator = PeerAuthValidator(
        jwkSource = mockk { every { find("kc") } returns keycloakKey.toPublicJWK() },
        replayProtectionService = DpopReplayProtectionService(inMemoryReplayRepository(), clock = TEST_CLOCK),
        expectedIssuer = "kc",
        expectedAudience = "orch",
        maxClockSkewSeconds = 30,
        maxAssertionAgeSeconds = 30,
        clock = TEST_CLOCK
    )
    val signer = mockk<KeycloakResponseSigner> { every { sign(any(), any(), any()) } returns "signed" }

    fun assertion(key: ECKey, signedBody: String = body): String {
        val claims = JWTClaimsSet.Builder()
            .issuer("kc").audience("orch").jwtID(UUID.randomUUID().toString()).issueTime(Date.from(TEST_NOW))
            .claim("htm", "PATCH").claim("htu", htu).claim("channel_binding", "abc")
            .claim("body_sha256", PeerAuthBodyCaptureFilter.sha256(signedBody.toByteArray()))
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(PeerAuthValidator.ASSERTION_TYPE).keyID("kc").build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(key)) }.serialize()
    }

    /** Both filters in their order, around a handler that answers 200. */
    fun send(token: String, sentBody: String = body): MockHttpServletResponse {
        val request = MockHttpServletRequest("PATCH", path).apply {
            addHeader("Authorization", "Bearer $token")
            setContent(sentBody.toByteArray())
        }
        val response = MockHttpServletResponse()
        val handler = FilterChain { _, res -> (res as jakarta.servlet.http.HttpServletResponse).status = 200 }
        PeerAuthBodyCaptureFilter().doFilter(request, response) { req, res ->
            KeycloakResponseSigningFilter(signer, validator).doFilter(req, res, handler)
        }
        return response
    }

    given("an assertion signed with Keycloak's key for this request and body") {
        `when`("the request passes the filters") {
            val response = send(assertion(keycloakKey))

            then("the answer is signed") {
                response.getHeader(KeycloakResponseSigner.HEADER) shouldBe "signed"
            }
        }
    }

    given("an assertion signed with some other key") {
        `when`("the request passes the filters") {
            val response = send(assertion(ECKeyGenerator(Curve.P_256).keyID("kc").generate()))

            then("the answer stays unsigned - no signature oracle") {
                response.getHeader(KeycloakResponseSigner.HEADER) shouldBe null
            }
        }
    }

    given("a valid assertion whose body was swapped on the way") {
        `when`("the request passes the filters") {
            val response = send(assertion(keycloakKey), sentBody = """{"targetAcr":"loa1","subject":{"type":"account","id":"42"}}""")

            then("the answer stays unsigned, so Keycloak rejects it") {
                response.getHeader(KeycloakResponseSigner.HEADER) shouldBe null
            }
        }
    }
})
