package com.example.identity.core.orchestrator

import com.example.identity.core.account.ChangeLogLookupKeys
import com.example.identity.core.account.ClaimEncryptionKeys
import com.example.identity.core.orchestrator.session.MockTokenProvider
import com.example.identity.core.orchestrator.session.TokenProvider
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
    @Value("\${identity.secrets.master-kek:}") private val masterKek: String,
    @Value("\${keycloak-tls.trust-self-signed:false}") private val trustSelfSigned: Boolean,
    @Value("\${keycloak-migrate.base-url:}") private val keycloakBaseUrl: String,
    @Value("\${keycloak-setup.orchestrator-base-url:}") private val orchestratorBaseUrlForKeycloak: String,
    @Value("\${springdoc.api-docs.enabled:true}") private val apiDocs: Boolean,
    private val tokenProvider: TokenProvider,
    private val encryptionKeys: ClaimEncryptionKeys,
) {
    init {
        if (demoMode.on) {
            val log = LoggerFactory.getLogger(ProductionModeCheck::class.java)
            log.info("Demomodus: Demo-Voreinstellungen erlaubt, ProductionModeCheck prueft nichts.")
            lookupKeys.orphanedKeyIds().takeIf { it.isNotEmpty() }?.let { log.warn(orphanedKeysMessage(it)) }
            encryptionKeys.orphanedKekVersions().takeIf { it.isNotEmpty() }?.let { log.warn(orphanedKekMessage(it)) }
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
        } else if (HASH_ENCODERS.none { adminPassword.startsWith(it) }) {
            add("demo.admin.password ist kein Hash. Angeben als {bcrypt}..., {argon2}..., {scrypt}... oder {pbkdf2}..., nicht im Klartext oder als {noop}.")
        }
        // Ohne das Profil keycloak stellt der Mock-TokenService unsignierte Tokens aus, die jeder faelschen kann.
        if (tokenProvider is MockTokenProvider) {
            add("Das Profil keycloak ist nicht aktiv - der App-Kanal gaebe unsignierte Mock-Tokens aus (SPRING_PROFILES_ACTIVE=keycloak).")
        }
        if (h2Console) add("spring.h2.console.enabled ist an - die Konsole liest und schreibt die ganze Datenbank. Abschalten.")
        if (apiDocs) add("springdoc.api-docs.enabled ist an - /v3/api-docs nennt jedem ohne Anmeldung alle Endpunkte. Abschalten.")
        if (otpPepper.length < MIN_SECRET_LENGTH) {
            add("identity.secrets.otp-pepper ist leer oder kuerzer als $MIN_SECRET_LENGTH Zeichen - Codes waeren durchprobierbar bzw. nach jedem Neustart ungueltig.")
        }
        if (lookupSecret.length < MIN_SECRET_LENGTH) {
            add("account.change-log.lookup-secret ist leer oder kuerzer als $MIN_SECRET_LENGTH Zeichen (CHANGE_LOG_LOOKUP_SECRET).")
        } else if (lookupKeys.usesDemoSecret()) {
            add("account.change-log.lookup-secret ist der oeffentliche Demo-Wert - Suchschluessel waeren fuer jeden umkehrbar (CHANGE_LOG_LOOKUP_SECRET).")
        }
        lookupKeys.orphanedKeyIds().takeIf { it.isNotEmpty() }?.let { add(orphanedKeysMessage(it)) }
        if (masterKek.length < MIN_SECRET_LENGTH) {
            // A blank value never gets here: ConfiguredKekWrapper refuses it at construction, in any mode.
            add("identity.secrets.master-kek ist kuerzer als $MIN_SECRET_LENGTH Zeichen (MASTER_KEK).")
        } else if (encryptionKeys.usesDemoKek()) {
            add("identity.secrets.master-kek ist der oeffentliche Demo-Wert - jeder damit eingepackte Schluessel waere fuer jeden lesbar (MASTER_KEK).")
        }
        encryptionKeys.orphanedKekVersions().takeIf { it.isNotEmpty() }?.let { add(orphanedKekMessage(it)) }
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

    private fun orphanedKekMessage(versions: Set<String>) =
        "account.account traegt Hauptschluessel mit KEK-Version $versions, fuer die kein Geheimnis konfiguriert ist - " +
            "diese Konten koennen keine Angabe mehr lesen (identity.secrets.previous-master-keks)."

    private companion object {
        const val DEMO_ADMIN_PASSWORD = "admin"

        /** Spring's ids for real password hashes; `{noop}` is plain text in disguise. */
        val HASH_ENCODERS = listOf("{bcrypt}", "{argon2}", "{argon2@SpringSecurity_v5_8}", "{scrypt}", "{scrypt@SpringSecurity_v5_8}", "{pbkdf2}", "{pbkdf2@SpringSecurity_v5_8}")

        /** 32 characters: 256 bits for a random hex or base64 value, the size of the HMAC key. */
        const val MIN_SECRET_LENGTH = 32
    }
}
