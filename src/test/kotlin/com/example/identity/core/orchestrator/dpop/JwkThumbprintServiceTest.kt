package com.example.identity.core.orchestrator.dpop

import com.nimbusds.jose.jwk.JWK
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * RFC 7638 thumbprint of the EC key from RFC 7517 A.1. frontend/src/deviceKey.test.ts expects the
 * same value, so frontend and backend agree on `bindingKeyRef`.
 */
class JwkThumbprintServiceTest : BehaviorSpec({
    given("the EC key from RFC 7517 A.1") {
        val jwk = JWK.parse(
            """{"kty":"EC","crv":"P-256","x":"MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4","y":"4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM"}"""
        )

        `when`("its thumbprint is computed") {
            val thumbprint = JwkThumbprintService().computeThumbprint(jwk)

            then("the members stand in lexicographic order, as RFC 7638 demands") {
                thumbprint shouldBe "cn-I_WNMClehiVp51i_0VpOENW1upEerA8sEam5hn-s"
            }
        }
    }
})
