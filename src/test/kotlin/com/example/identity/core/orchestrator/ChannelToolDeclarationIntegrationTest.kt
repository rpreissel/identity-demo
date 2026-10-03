package com.example.identity.core.orchestrator

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

/**
 * A client declares which tools it can render (`availableTools`). The channel stores only what the
 * catalog knows: free strings from a cheap DPoP key never reach the table.
 */
class ChannelToolDeclarationIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    init {
        given("an App client declaring a catalog tool and a made-up one") {
            `when`("it opens a channel") {
                val made = "x".repeat(500) + "<script>"
                val channelSessionId = post(
                    "/orchestrator/api/v1/app/channels", """{"availableTools":["ident-fsc","$made"]}"""
                ).channel()["channelSessionId"] as String

                val stored = jdbcTemplate.queryForObject(
                    "select cast(available_tools as varchar) from orchestrator.channel_session where id = ?",
                    String::class.java, UUID.fromString(channelSessionId)
                )!!

                then("the channel keeps the catalog tool and drops the rest") {
                    stored shouldContain "ident-fsc"
                    stored shouldNotContain "<script>"
                }
            }
        }
    }
}
