package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.keycloak.KeycloakRealmLoginTheme
import com.example.identity.core.orchestrator.keycloak.LoginTheme
import com.example.identity.core.orchestrator.domain.FeatureFlags
import com.example.identity.core.orchestrator.session.FeatureFlagService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The runtime switch between the two login themes (ADR-41). The orchestrator's flag is the source
 * of truth, and the realm follows it: on every [switchTo], and once at start, because a rebuilt
 * realm starts with FreeMarker again. Runs right after the Keycloak migrations.
 */
@Component
@Profile("keycloak")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class LoginThemeSwitch(
    private val featureFlagService: FeatureFlagService,
    private val realmLoginTheme: KeycloakRealmLoginTheme,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(LoginThemeSwitch::class.java)

    fun current(): LoginTheme =
        if (featureFlagService.isEnabled(FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY)) LoginTheme.KEYCLOAKIFY else LoginTheme.FREEMARKER

    /**
     * Realm first, flag second: if Keycloak refuses, the flag still says what the realm shows and
     * the caller sees the error. Takes effect with the very next page Keycloak renders.
     */
    fun switchTo(theme: LoginTheme) {
        realmLoginTheme.apply(theme)
        featureFlagService.setEnabled(FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY, theme == LoginTheme.KEYCLOAKIFY)
    }

    override fun run(args: ApplicationArguments) {
        val theme = current()
        // A theme that cannot be set must not keep the orchestrator from starting.
        runCatching { realmLoginTheme.apply(theme) }
            .onSuccess { log.info("Keycloak-Login-Theme auf {} abgeglichen.", theme) }
            .onFailure { log.warn("Keycloak-Login-Theme konnte nicht auf {} gesetzt werden", theme, it) }
    }
}
