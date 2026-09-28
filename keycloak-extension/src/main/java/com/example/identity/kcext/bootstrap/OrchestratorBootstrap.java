package com.example.identity.kcext.bootstrap;

import org.keycloak.provider.Provider;

/**
 * Was die Extension beim Keycloak-Start einrichtet, bevor es ein Realm des Orchestrators gibt. Die
 * Arbeit steckt in der Factory ({@code postInit}). Eine eigene SPI, damit die Konfiguration einen
 * sprechenden Namen bekommt ({@code spi-orchestrator-bootstrap-...}).
 */
public interface OrchestratorBootstrap extends Provider {
}
