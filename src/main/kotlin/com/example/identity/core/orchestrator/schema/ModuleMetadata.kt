package com.example.identity.core.orchestrator.schema

/**
 * How the database schema comes into being: which migrations Flyway runs
 * ([ModuleMigrationLocations]) and what happens when a local database no longer matches them
 * ([FlywayResetConfig]).
 *
 * Depends on nothing inside the orchestrator, and must not: it runs before any of it exists.
 */
internal object SchemaPackageMarker
