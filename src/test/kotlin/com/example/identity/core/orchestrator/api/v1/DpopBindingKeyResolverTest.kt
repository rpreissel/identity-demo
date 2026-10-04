package com.example.identity.core.orchestrator.api.v1

import com.example.identity.core.orchestrator.dpop.DpopFailure
import com.example.identity.core.orchestrator.dpop.DpopValidationException
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Only an App channel carries a key (docs/invarianten.md I-8): an endpoint of the App channel alone
 * (`@BindingKey(dpopOnly = true)`) takes no peer-auth assertion, not even a valid one.
 */
class DpopBindingKeyResolverTest : BehaviorSpec({

    given("a request with a peer-auth assertion and no DPoP proof") {
        val peerAuthValidator = mockk<PeerAuthValidator>(relaxed = true)
        val resolver = DpopBindingKeyResolver(mockk(), mockk(), peerAuthValidator)
        val request = MockHttpServletRequest("POST", "/orchestrator/api/v1/app/channels").apply {
            addHeader("Authorization", "Bearer some.peer-auth.assertion")
        }

        `when`("it reaches an endpoint of the App channel alone") {
            val result = runCatching { resolver.bindingKeyOf(request, dpopOnly = true) }

            then("it is refused as a missing DPoP proof, before the assertion is even looked at") {
                shouldThrow<DpopValidationException> { result.getOrThrow() }.failure shouldBe DpopFailure.MISSING
                verify(exactly = 0) { peerAuthValidator.validate(any(), any(), any(), any()) }
            }
        }
    }
})
