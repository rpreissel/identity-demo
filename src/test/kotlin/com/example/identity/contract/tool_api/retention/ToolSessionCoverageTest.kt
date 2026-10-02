package com.example.identity.contract.tool_api.retention

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * Every tool-session table is swept, so no module keeps submitted data indefinitely.
 *
 * The table list is read from the live schema, not hard-coded, so a new module's table is covered
 * as soon as its migration lands.
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolSessionCoverageTest : BehaviorSpec() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var sweepers: List<ToolSessionSweeper>

    init {
        given("one long-expired row in every *_tool_session table of the schema") {
            val tables = toolSessionTables()
            tables.shouldNotBeEmptyList()
            val expired = Instant.now().minusSeconds(365 * 24 * 3600)
            tables.forEach { table -> insertExpiredRow(table, expired) }

            `when`("every module's sweeper runs as the driver runs it") {
                sweepers.forEach { it.sweep(Instant.now().minusSeconds(24 * 3600)) }

                then("no table keeps its expired row") {
                    // A table left over names the module that lacks a ToolSessionSweeper.
                    val stillPopulated = tables.filter { table ->
                        val remaining = jdbcTemplate.queryForObject(
                            "select count(*) from $table where created_at < ?", Long::class.java, java.sql.Timestamp.from(expired.plusSeconds(1))
                        ) ?: 0L
                        remaining > 0
                    }
                    stillPopulated.shouldBeEmpty()
                }
            }
        }
    }

    /**
     * `orchestrator.tool_session` is excluded: it is the orchestrator's own session bookkeeping,
     * swept by `orchestrator.session.RetentionJob` together with channels and journeys, not by a
     * method module's sweeper.
     */
    private fun toolSessionTables(): List<String> =
        jdbcTemplate.queryForList(
            """
            select table_schema || '.' || table_name as t
            from information_schema.tables
            where table_name like '%TOOL_SESSION' and table_schema <> 'ORCHESTRATOR'
            """.trimIndent(),
            String::class.java
        ).requireNoNulls().sorted()

    /**
     * Only `created_at` is set; other columns take their default or NULL. A new NOT NULL column
     * without default makes this insert fail, which is intended.
     */
    private fun insertExpiredRow(table: String, createdAt: Instant) {
        val idColumn = jdbcTemplate.queryForList(
            """
            select column_name from information_schema.columns
            where table_schema || '.' || table_name = ? and is_nullable = 'NO' and column_default is null
              and column_name <> 'CREATED_AT'
            """.trimIndent(),
            String::class.java, table
        ).requireNoNulls()
        val columns = (listOf("created_at") + idColumn).joinToString(", ")
        val values = (listOf(java.sql.Timestamp.from(createdAt)) + idColumn.map { placeholderFor(table, it) }).toTypedArray()
        jdbcTemplate.update(
            "insert into $table ($columns) values (${values.joinToString(", ") { "?" }})",
            *values
        )
    }

    /**
     * A valid value of the right type; only the row's age matters. Text values respect the declared
     * length, since some columns are narrow (a PIN, an activation code).
     */
    private fun placeholderFor(table: String, column: String): Any {
        val meta = jdbcTemplate.queryForMap(
            """
            select data_type, character_maximum_length
            from information_schema.columns
            where table_schema || '.' || table_name = ? and column_name = ?
            """.trimIndent(),
            table, column
        )
        val type = meta["DATA_TYPE"]?.toString().orEmpty()
        val maxLength = (meta["CHARACTER_MAXIMUM_LENGTH"] as? Number)?.toInt() ?: Int.MAX_VALUE
        return when {
            type.contains("UUID") -> java.util.UUID.randomUUID()
            type.contains("CHAR") -> java.util.UUID.randomUUID().toString().replace("-", "").take(minOf(maxLength, 32))
            type.contains("BOOLEAN") -> false
            type.contains("TIMESTAMP") || type.contains("DATE") -> java.sql.Timestamp.from(Instant.now())
            type.contains("INT") || type.contains("NUMERIC") || type.contains("DECIMAL") -> 1L
            else -> java.util.UUID.randomUUID().toString().take(minOf(maxLength, 32))
        }
    }

    private fun List<String>.shouldNotBeEmptyList() {
        check(isNotEmpty()) { "Keine *_tool_session-Tabellen gefunden - das Schema wurde nicht migriert?" }
    }
}
