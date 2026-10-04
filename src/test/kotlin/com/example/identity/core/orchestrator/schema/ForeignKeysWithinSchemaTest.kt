package com.example.identity.core.orchestrator.schema

import com.example.identity.core.orchestrator.SharedSpringContext
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Foreign keys stay inside one module's schema (ADR-16, docs/02-domaenenmodell.md Abschnitt 7): a
 * reference into another module is a column with an index, cleaned up through that module's API.
 * Read from the migrated schema, so a new migration is checked as soon as it lands.
 */
class ForeignKeysWithinSchemaTest(private val jdbcTemplate: JdbcTemplate) : SharedSpringContext({

    given("the foreign keys of the migrated schema") {
        val foreignKeys = jdbcTemplate.queryForList(
            """
            select constraint_schema || '.' || constraint_name as fk, unique_constraint_schema as target_schema
            from information_schema.referential_constraints
            """.trimIndent()
        )

        then("there are some, so the check below is not empty by accident") {
            foreignKeys.shouldNotBeEmpty()
        }
        then("none of them references a table in another schema") {
            foreignKeys.filter { row -> row["FK"].toString().substringBefore('.') != row["TARGET_SCHEMA"].toString() }
                .map { "${it["FK"]} -> ${it["TARGET_SCHEMA"]}" }
                .shouldBeEmpty()
        }
    }
})
