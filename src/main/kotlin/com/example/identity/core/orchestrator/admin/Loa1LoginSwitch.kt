package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.keycloak.KeycloakFeatureFlags
import com.example.identity.core.orchestrator.keycloak.KeycloakLoa1Login
import com.example.identity.core.orchestrator.keycloak.Loa1Login
import com.example.identity.core.orchestrator.session.FeatureFlagService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The runtime switch of what the web channel's `loa1` asks for (ADR-42), built like
 * [LoginThemeSwitch]: the flag is the source of truth, the realm follows it on every [switchTo] and
 * once at start, because a rebuilt realm starts with the method selection again.
 */
@Component
@Profile("keycloak")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class Loa1LoginSwitch(
    private val featureFlagService: FeatureFlagService,
    private val keycloakLoa1Login: KeycloakLoa1Login,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(Loa1LoginSwitch::class.java)

    fun current(): Loa1Login =
        if (featureFlagService.isEnabled(KeycloakFeatureFlags.LOA1_PASSWORD)) Loa1Login.KEYCLOAK_PASSWORD else Loa1Login.ORCHESTRATOR

    /** Realm first, flag second: if Keycloak refuses, the flag still says what the realm does. */
    fun switchTo(login: Loa1Login) {
        keycloakLoa1Login.apply(login)
        featureFlagService.setEnabled(KeycloakFeatureFlags.LOA1_PASSWORD, login == Loa1Login.KEYCLOAK_PASSWORD)
    }

    override fun run(args: ApplicationArguments) {
        val login = current()
        // A flow that cannot be set must not keep the orchestrator from starting.
        runCatching { keycloakLoa1Login.apply(login) }
            .onSuccess { log.info("Keycloak-Anmeldung auf loa1 auf {} abgeglichen.", login) }
            .onFailure { log.warn("Keycloak-Anmeldung auf loa1 konnte nicht auf {} gesetzt werden", login, it) }
    }
}
