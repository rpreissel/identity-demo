package com.example.identity.core.orchestrator.dpop

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/** RFC 9449 4.3: scheme and host without case, default port, path exact. */
class HtuMatchesTest : BehaviorSpec({
    val request = "https://api.example.org/orchestrator/api/v1/tools/abc/auth-sms"

    given("a request to $request") {
        val sameTarget = mapOf(
            "the htu adds a query and a fragment" to "https://api.example.org/orchestrator/api/v1/tools/abc/auth-sms?x=1#f",
            "the htu writes scheme and host in other case" to "HTTPS://API.Example.ORG/orchestrator/api/v1/tools/abc/auth-sms",
            "the htu writes out the default port" to "https://api.example.org:443/orchestrator/api/v1/tools/abc/auth-sms"
        )
        val otherTarget = mapOf(
            "the htu writes the path in other case" to "https://api.example.org/Orchestrator/api/v1/tools/abc/auth-sms",
            "the htu names another path" to "https://api.example.org/orchestrator/api/v1/tools/abd/auth-sms",
            "the htu names another port" to "https://api.example.org:8443/orchestrator/api/v1/tools/abc/auth-sms",
            "the htu uses http instead of https" to "http://api.example.org/orchestrator/api/v1/tools/abc/auth-sms",
            "the htu is a bare path" to "/orchestrator/api/v1/tools/abc/auth-sms",
            "the htu uses a scheme other than http(s)" to "ftp://api.example.org/orchestrator/api/v1/tools/abc/auth-sms"
        )

        sameTarget.forEach { (variant, htu) ->
            `when`(variant) {
                val matches = htuMatches(htu, request)

                then("it matches") {
                    matches shouldBe true
                }
            }
        }

        otherTarget.forEach { (variant, htu) ->
            `when`(variant) {
                val matches = htuMatches(htu, request)

                then("it does not match") {
                    matches shouldBe false
                }
            }
        }
    }
})
