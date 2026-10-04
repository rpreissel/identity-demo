package com.example.identity.core.orchestrator.keycloak

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * The body a peer-auth assertion is checked against is the body the handler reads: hashed once,
 * before anything parses it, and handed on unchanged (ADR-7).
 */
class PeerAuthBodyCaptureFilterTest : BehaviorSpec({

    given("a Keycloak request with a JSON body") {
        val body = """{"subject":{"type":"account","id":"7"}}"""
        val request = MockHttpServletRequest("PATCH", "/orchestrator/api/v1/kc/channels/abc").apply {
            addHeader("Authorization", "Bearer x.y.z")
            setContent(body.toByteArray())
            queryString = "kcSessionId=s1"
        }

        `when`("it passes the filter") {
            var seen: String? = null
            PeerAuthBodyCaptureFilter().doFilter(request, MockHttpServletResponse()) { req, _ ->
                seen = String((req as HttpServletRequest).inputStream.readAllBytes())
            }

            then("the hash of exactly that body is there for the check") {
                peerAuthBodySha256(request) shouldBe PeerAuthBodyCaptureFilter.sha256(body.toByteArray())
            }
            then("the handler still reads the whole body") {
                seen shouldBe body
            }
            then("the checked address carries the query") {
                peerAuthTarget(request) shouldBe "http://localhost/orchestrator/api/v1/kc/channels/abc?kcSessionId=s1"
            }
        }
    }
})
