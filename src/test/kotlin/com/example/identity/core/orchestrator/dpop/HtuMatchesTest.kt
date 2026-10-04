package com.example.identity.core.orchestrator.dpop

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/** RFC 9449 4.3: scheme and host without case, default port, path exact. */
class HtuMatchesTest : BehaviorSpec({
    val request = "https://api.example.org/tools/api/auth-sms/v1/abc"

    given("a request to $request") {
        val sameTarget = mapOf(
            "the htu adds a query and a fragment" to "https://api.example.org/tools/api/auth-sms/v1/abc?x=1#f",
            "the htu writes scheme and host in other case" to "HTTPS://API.Example.ORG/tools/api/auth-sms/v1/abc",
            "the htu writes out the default port" to "https://api.example.org:443/tools/api/auth-sms/v1/abc"
        )
        val otherTarget = mapOf(
            "the htu writes the path in other case" to "https://api.example.org/Tools/api/auth-sms/v1/abc",
            "the htu names another path" to "https://api.example.org/tools/api/auth-sms/v1/abd",
            "the htu names another port" to "https://api.example.org:8443/tools/api/auth-sms/v1/abc",
            "the htu uses http instead of https" to "http://api.example.org/tools/api/auth-sms/v1/abc",
            "the htu is a bare path" to "/tools/api/auth-sms/v1/abc",
            "the htu uses a scheme other than http(s)" to "ftp://api.example.org/tools/api/auth-sms/v1/abc"
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
