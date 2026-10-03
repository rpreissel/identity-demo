package com.example.identity.contract.tool_api.retention

import com.example.identity.core.orchestrator.SharedSpringContext
import io.kotest.matchers.collections.shouldContainExactly
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * A tool keeps its working data through `ToolSessionData`, as JSON at `orchestrator.tool_session`
 * (ADR-49), which ends with that row. A module bringing its own `*_tool_session` table again would
 * keep submitted data that nobody cleans up. The tables are read from the live schema, so a new
 * module's migration is covered as soon as it lands.
 */
class ToolSessionCoverageTest : SharedSpringContext() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    init {
        given("the live schema") {
            then("the only tool-session table is the orchestrator's") {
                val tables = jdbcTemplate.queryForList(
                    "select table_schema || '.' || table_name from information_schema.tables where table_name like '%TOOL_SESSION'",
                    String::class.java,
                ).requireNoNulls().map { it.lowercase() }
                tables shouldContainExactly listOf("orchestrator.tool_session")
            }
        }
    }
}
