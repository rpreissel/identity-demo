package com.example.identity.core.orchestrator.schema

import com.example.identity.demo.demo_mode.DemoMode
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Lets every module keep its migrations in its own folder `db/migration/<module>/` (ADR-16). The
 * folders are discovered, not listed, so a new module cannot be forgotten in a central list.
 * Versions remain one sequence across all folders; two modules using the same version fail the
 * start.
 */
@Configuration
class ModuleMigrationLocations(private val demoMode: DemoMode) {

    @Bean
    fun perModuleMigrationLocations(): FlywayConfigurationCustomizer = FlywayConfigurationCustomizer { configuration ->
        val discovered = moduleLocations()
        if (discovered.isEmpty()) return@FlywayConfigurationCustomizer
        configuration.locations(*(configuration.locations.map { it.descriptor } + discovered).toTypedArray())
        log.info("Flyway: {} modulspezifische Migrationsverzeichnisse gefunden: {}", discovered.size, discovered)
    }

    /** The module folders this instance migrates - all of them, minus the demo data outside demo mode. */
    internal fun moduleLocations(): List<String> = discoverModuleLocations().filter { demoMode.on || it !in DEMO_ONLY_LOCATIONS }

    /**
     * Every immediate subdirectory of `db/migration` that contains a migration. `classpath*:`
     * searches all jars, so a separately packaged module is found too.
     */
    private fun discoverModuleLocations(): List<String> =
        PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/*/*.sql")
            .mapNotNull { resource ->
                val path = resource.url.toString()
                val marker = "db/migration/"
                val folder = path.substringAfterLast(marker, "").substringBeforeLast('/', "")
                folder.takeIf { it.isNotEmpty() && !it.contains('/') }
            }
            .distinct()
            .sorted()
            .map { "classpath:db/migration/$it" }

    private companion object {
        /**
         * The demo personas and their letters, and the readable views on sealed columns (ADR-55),
         * which must not appear in a real database. A database that ran them cannot leave demo
         * mode; Flyway then refuses the unknown applied migration.
         */
        val DEMO_ONLY_LOCATIONS = setOf("classpath:db/migration/demo_seed", "classpath:db/migration/demo_views")

        private val log = LoggerFactory.getLogger(ModuleMigrationLocations::class.java)
    }
}
