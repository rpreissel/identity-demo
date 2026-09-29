package com.example.identity.tools.ident_nect.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where a channel may have Nect send the user back to (docs/adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md). The app
 * channel names no address and gets `/app/`; the web channel names Keycloak's action URL of the
 * running step, so the prefixes are that Keycloak's `login-actions` (application-keycloak.yml).
 */
@ConfigurationProperties(prefix = "ident-nect")
data class IdentNectProperties(
    val returnUriPrefixes: List<String> = emptyList()
)
