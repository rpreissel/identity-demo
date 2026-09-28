package com.example.identity.core.orchestrator

import com.example.identity.core.account.ChangeLogLookupKeys
import com.example.identity.demo.demo_mode.DemoMode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Refuses to start outside demo mode while a demo default is still in place. `demo.mode=false` is
 * the promise that real people's data may be here (ADR-35). One list names every violation at once,
 * so a deployment is not fixed by trial and error. Runs before the web server accepts a request.
 */
@Component
class ProductionModeCheck(
    private val demoMode: DemoMode,
    private val lookupKeys: ChangeLogLookupKeys,
    @Value("\${demo.admin.password:}") private val adminPassword: String,
    @Value("\${spring.h2.console.enabled:false}") private val h2Console: Boolean,
    @Value("\${identity.secrets.otp-pepper:}") private val otpPepper: String,
    @Value("\${account.change-log.lookup-secret:}") private val lookupSecret: String,
    @Value("\${keycloak-tls.trust-self-signed:false}") private val trustSelfSigned: Boolean,
    @Value("\${keycloak-migrate.base-url:}") private val keycloakBaseUrl: String,
    @Value("\${keycloak-setup.orchestrator-base-url:}") private val orchestratorBaseUrlForKeycloak: String,
) {
    init {
        if (demoMode.on) {
            val log = LoggerFactory.getLogger(ProductionModeCheck::class.java)
            log.info("Demomodus: Demo-Voreinstellungen erlaubt, ProductionModeCheck prueft nichts.")
            lookupKeys.orphanedKeyIds().takeIf { it.isNotEmpty() }?.let { log.warn(orphanedKeysMessage(it)) }
        } else {
            val violations = violations()
            check(violations.isEmpty()) {
                "demo.mode=false, aber Demo-Voreinstellungen sind noch gesetzt:\n" + violations.joinToString("\n") { "- $it" }
            }
        }
    }

    /** What stands between this configuration and one fit for real people - empty when nothing does. */
    fun violations(): List<String> = buildList {
        if (adminPassword.isBlank() || adminPassword == DEMO_ADMIN_PASSWORD) {
            add("demo.admin.password ist leer oder der Demo-Wert. Ein eigenes setzen (DEMO_ADMIN_PASSWORD).")
        } else if (!adminPassword.startsWith("{")) {
            add("demo.admin.password steht im Klartext. Als Hash angeben, z. B. {bcrypt}... oder {argon2}...")
        }
        if (h2Console) add("spring.h2.console.enabled ist an - die Konsole liest und schreibt die ganze Datenbank. Abschalten.")
        if (otpPepper.length < MIN_SECRET_LENGTH) {
            add("identity.secrets.otp-pepper ist leer oder kuerzer als $MIN_SECRET_LENGTH Zeichen - Codes waeren durchprobierbar bzw. nach jedem Neustart ungueltig.")
        }
        if (lookupSecret.length < MIN_SECRET_LENGTH) {
            add("account.change-log.lookup-secret ist leer oder kuerzer als $MIN_SECRET_LENGTH Zeichen (CHANGE_LOG_LOOKUP_SECRET).")
        } else if (lookupKeys.usesDemoSecret()) {
            add("account.change-log.lookup-secret ist der oeffentliche Demo-Wert - Suchschluessel waeren fuer jeden umkehrbar (CHANGE_LOG_LOOKUP_SECRET).")
        }
        lookupKeys.orphanedKeyIds().takeIf { it.isNotEmpty() }?.let { add(orphanedKeysMessage(it)) }
        if (trustSelfSigned) add("Das Zertifikat von Keycloak wird nicht geprueft (trustSelfSignedCertificate). Ein vertrauenswuerdiges Zertifikat verwenden.")
        if (keycloakBaseUrl.isNotBlank() && !keycloakBaseUrl.startsWith("https://")) {
            add("Keycloak wird ueber $keycloakBaseUrl erreicht, nicht ueber https.")
        }
        // Keycloak holt ueber diesen Weg die Schluessel fuer Client-Anmeldung und Antwortsignatur.
        // Ohne geprueftes TLS koennte, wer den Weg kontrolliert, eigene Schluessel unterschieben.
        if (orchestratorBaseUrlForKeycloak.isNotBlank() && !orchestratorBaseUrlForKeycloak.startsWith("https://")) {
            add("Keycloak erreicht den Orchestrator ueber $orchestratorBaseUrlForKeycloak, nicht ueber https - darueber laufen die Schluessel fuer Client-Anmeldung und Antwortsignatur.")
        }
    }

    private fun orphanedKeysMessage(ids: Set<String>) =
        "account.change_log traegt Suchschluessel mit Id $ids, fuer die kein Geheimnis konfiguriert ist - " +
            "diese Eintraege sind nicht mehr nach Namen zu finden (account.change-log.previous-lookup-secrets)."

    private companion object {
        const val DEMO_ADMIN_PASSWORD = "admin"

        /** 32 characters: 256 bits for a random hex or base64 value, the size of the HMAC key. */
        const val MIN_SECRET_LENGTH = 32
    }
}
