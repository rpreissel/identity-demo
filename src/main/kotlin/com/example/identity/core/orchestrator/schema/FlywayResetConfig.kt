package com.example.identity.core.orchestrator.schema

import com.example.identity.demo.demo_mode.OnlyInDemoMode
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.api.FlywayException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import javax.sql.DataSource

/**
 * In demo mode, migrations are edited in place, so an older local H2 file fails Flyway's
 * validation. There is nothing in it worth protecting: if migrate() fails, the H2 file is deleted
 * and a fresh one is migrated.
 */
@Configuration
// Demo mode only: outside it a failed migration must stop the start rather than wipe the data.
@OnlyInDemoMode
class FlywayResetConfig {
    private val log = LoggerFactory.getLogger(FlywayResetConfig::class.java)

    @Bean
    fun flywayMigrationStrategy(@Value("\${spring.datasource.url}") jdbcUrl: String, dataSource: DataSource): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            try {
                flyway.migrate()
            } catch (e: FlywayException) {
                // A database of the other encryption mode is not broken: it is kept, and the start stops (ADR-55).
                generateSequence<Throwable>(e) { it.cause }.filterIsInstance<EncryptionModeGuard.ModeMismatch>().firstOrNull()?.let { throw it }
                log.warn(
                    "Flyway migration failed ({}) - deleting the H2 database file and recreating it " +
                        "from scratch. Demo-only recovery: this discards all existing data.",
                    e.message
                )
                deleteH2DatabaseFiles(jdbcUrl)
                // An open H2 connection keeps serving the deleted database from memory. Evict the
                // pool so the retry opens a fresh connection against the new file.
                (dataSource as? HikariDataSource)?.hikariPoolMXBean?.softEvictConnections()
                flyway.migrate()
            }
        }

    private fun deleteH2DatabaseFiles(jdbcUrl: String) {
        val prefix = "jdbc:h2:file:"
        if (!jdbcUrl.startsWith(prefix)) return
        val path = jdbcUrl.removePrefix(prefix).substringBefore(";")
        listOf(".mv.db", ".trace.db").forEach { suffix ->
            val file = File("$path$suffix")
            if (file.exists() && file.delete()) {
                log.warn("Deleted {}", file.path)
            }
        }
    }
}
