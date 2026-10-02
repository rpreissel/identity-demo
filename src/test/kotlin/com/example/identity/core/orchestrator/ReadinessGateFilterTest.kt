package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.kc.CLIENT_JWKS_PATH
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * During the Keycloak migrations the gate blocks everything except the client JWKS: Keycloak checks
 * the migration's own sign-in against it. Were it behind the gate, Keycloak would reject the
 * assertion and the orchestrator would never start.
 */
class ReadinessGateFilterTest : BehaviorSpec({

    given("an orchestrator still running its Keycloak migrations") {
        val starting = object : ReadinessState {
            override val isReady = false
        }

        fun call(uri: String): Int {
            val response = MockHttpServletResponse()
            ReadinessGateFilter(starting).doFilter(MockHttpServletRequest("GET", uri), response, MockFilterChain())
            return response.status
        }

        `when`("an ordinary request arrives") {
            val status = call("/orchestrator/api/v1/app/channels")

            then("it is blocked with 503") {
                status shouldBe 503
            }
        }

        `when`("Keycloak fetches the client JWKS") {
            val status = call("$CLIENT_JWKS_PATH/.well-known/jwks.json")

            then("it passes already") {
                status shouldBe 200
            }
        }

        `when`("a request arrives on a path that only starts like the client JWKS") {
            val status = call("${CLIENT_JWKS_PATH}-other/x")

            then("it is blocked - only exactly that path is exempt") {
                status shouldBe 503
            }
        }
    }
})
