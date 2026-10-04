package com.example.identity.core.orchestrator

import io.kotest.matchers.shouldBe

/**
 * A finished tool run keeps no working data: ident-fsc's holds KVNR, name and date of birth, and
 * would otherwise lie unencrypted in orchestrator.tool_session until the retention sweep (ADR-49).
 */
class ToolSessionDataClearedIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    init {
        given("a registration") {
            `when`("ident-fsc identifies the person") {
                identify()

                then("its finished tool session carries no working data any more") {
                    jdbcTemplate.queryForObject(
                        "select count(*) from orchestrator.tool_session where status = 'DONE' and (data is not null or data_type is not null)",
                        Int::class.java
                    ) shouldBe 0
                    val done = jdbcTemplate.queryForObject("select count(*) from orchestrator.tool_session where status = 'DONE'", Int::class.java)
                    (done != null && done >= 1) shouldBe true
                }
            }
        }
    }
}
