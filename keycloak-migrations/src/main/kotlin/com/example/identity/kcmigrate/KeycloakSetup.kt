package com.example.identity.kcmigrate

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Eine Keycloak-Umgebung, in zwei Hälften (docs/13-ausfuehren.md #6): [realm] ist, was die
 * Migration ins Realm schreibt, und eine Änderung daran baut das Realm neu auf. [access] beschreibt
 * nur, wie man das fertige Realm erreicht, und gilt beim nächsten Start. Das Skript sieht nur
 * [realm], kann also keinen Wert verbauen, den der Reset nicht überwacht. Die Werte stehen in
 * application-keycloak.yml (`keycloak-setup.*`); diese Datei deklariert nur die Felder.
 */
data class KeycloakSetup(
    val realm: RealmSetup,
    val access: KeycloakAccess,
) {
    companion object {
        /** Alle Feldnamen beider Hälften - was eine Basis nennen muss und eine Variante nennen darf. */
        val FIELD_NAMES: Set<String> get() = RealmSetup.FIELD_NAMES + KeycloakAccess.FIELD_NAMES

        /**
         * Baut den Satz aus flach benannten Werten, wie sie in der Konfiguration stehen. Ein fehlendes
         * oder unbekanntes Feld lässt den Aufruf laut scheitern, damit ein Tippfehler auffällt.
         */
        fun from(values: Map<String, String>): KeycloakSetup {
            (values.keys - FIELD_NAMES).sorted().firstOrNull()?.let {
                error("Unbekanntes Feld \"$it\" in der Keycloak-Konfiguration - bekannt sind: ${FIELD_NAMES.sorted().joinToString(", ")}")
            }
            return KeycloakSetup(
                realm = build(RealmSetup::class, values),
                access = build(KeycloakAccess::class, values),
            )
        }
    }
}

/**
 * Was die Migration ins Realm schreibt. Jede Änderung an einem dieser Werte baut das Realm neu auf
 * ([MigrationRunner.resetOnSetupChange]); ein halb umkonfiguriertes Realm wäre schlimmer.
 */
data class RealmSetup(
    /** Das Realm, das aufgebaut und anschließend bedient wird. Alles hier lebt darin. */
    val realmName: String,
    /** Anzeigename dieses Realms auf den Login-Seiten. */
    val realmDisplayName: String,
    /**
     * Login-Theme des Realms beim Aufbau. Die Formulare der Extension leben darin; zur Laufzeit
     * schaltet der Orchestrator zwischen diesem und seinem Keycloakify-Kind-Theme um.
     */
    val loginTheme: String,

    /** Der öffentliche OIDC-Client, mit dem der Browser den Web-Kanal-Login fährt (PKCE, kein Secret). */
    val browserClientId: String,
    /**
     * Service-Account-Client für Keycloaks Admin-REST-API, mit der Rolle `manage-users`. Ohne Secret:
     * er authentisiert sich per `private_key_jwt` (ADR-25).
     */
    val adminApiClientId: String,
    /**
     * Rechtloser Client nur für den Custom-Grant `urn:identity-demo:account-token` (App-Kanal-Login),
     * getrennt von [adminApiClientId], damit dieser Pfad nie Admin-Rechte sieht.
     */
    val appTokenClientId: String,

    /**
     * Wohin Keycloak nach dem Login zurückleiten darf. Die CORS-Origins leitet der Browser-Client per
     * `+` daraus ab.
     */
    val browserRedirectUris: List<String>,

    /** Server-zu-Server-Adresse des Orchestrators, als Config-Property an der `orchestrator`-Komponente. */
    val orchestratorBaseUrl: String,
    /**
     * Derselbe Orchestrator, wie ein Browser ihn erreicht: Basis des QR-Deep-Links, der auf einem
     * fremden Gerät geöffnet wird. Ebenfalls Config-Property der Komponente.
     */
    val publicOrchestratorBaseUrl: String,
    /** `iss` der Assertion, mit der Keycloaks Extension sich beim Orchestrator ausweist (ADR-7). */
    val peerAuthIssuer: String,
    /** `aud` derselben Assertion - wen sie adressiert, also den Orchestrator. */
    val peerAuthAudience: String,
) {
    /** Die Werte in der Form, in der [MigrationRunner] sie mit dem letzten Lauf vergleicht. */
    fun asMap(): Map<String, String> = fieldsOf(this)

    companion object {
        val FIELD_NAMES = fieldNamesOf(RealmSetup::class)
    }
}

/**
 * Wie der Orchestrator und der Browser das fertige Realm erreichen. Reine Laufzeit - im Realm
 * steht davon nichts, eine Änderung gilt einfach beim nächsten Start und löst **keinen** Reset aus.
 */
data class KeycloakAccess(
    /**
     * Server-zu-Server-Adresse von Keycloak. Ohne Zugangsdaten: die Migration meldet sich als
     * `orchestrator-migration` per `private_key_jwt` an, den Client legt die Extension selbst an.
     */
    val keycloakBaseUrl: String,
    /**
     * Dasselbe Keycloak, wie ein Browser es erreicht. Der Token-Issuer trägt diese Adresse, weil
     * der Browser sein Token von dort bekommt - geprüft wird trotzdem gegen [keycloakBaseUrl].
     */
    val publicKeycloakBaseUrl: String,
    /**
     * Ob der Orchestrator einem selbstsignierten Zertifikat unter [keycloakBaseUrl] vertraut, nur für
     * Verbindungen zu Keycloak. Die Basis setzt `false`, eine Variante muss es einschalten.
     */
    val trustSelfSignedCertificate: Boolean,
) {
    companion object {
        val FIELD_NAMES = fieldNamesOf(KeycloakAccess::class)
    }
}

// --- Reflexions-Hilfen: ein neues Feld ist genau eine Zeile in seiner data class, ohne parallele
// Namensliste.

private fun fieldNamesOf(type: KClass<*>): Set<String> =
    type.primaryConstructor!!.parameters.mapNotNull { it.name }.toSet()

private fun <T : Any> fieldsOf(value: T): Map<String, String> =
    value::class.memberProperties
        .sortedBy { it.name }
        .associate { property ->
            @Suppress("UNCHECKED_CAST")
            property.name to render((property as kotlin.reflect.KProperty1<T, *>).get(value))
        }

private fun <T : Any> build(type: KClass<T>, values: Map<String, String>): T {
    val constructor = type.primaryConstructor!!
    return constructor.callBy(
        constructor.parameters.associateWith { field ->
            val raw = values[field.name]
                ?: error("Feld \"${field.name}\" fehlt in der Keycloak-Konfiguration (keycloak-setup.base)")
            parse(field, raw)
        },
    )
}

/** Listenfelder als kommagetrennte Zeichenkette - dieselbe Form, in der sie in der Konfiguration stehen. */
private fun render(value: Any?): String = when (value) {
    is List<*> -> value.joinToString(",")
    else -> value.toString()
}

private fun parse(field: KParameter, raw: String): Any =
    when (field.type.classifier) {
        List::class -> raw.split(",").map(String::trim).filter(String::isNotEmpty)
        Boolean::class -> raw.trim().toBooleanStrictOrNull()
            ?: error("Feld \"${field.name}\" erwartet true oder false, nicht \"$raw\"")
        else -> raw
    }

/**
 * Woher ein benannter Satz kommt. Der Orchestrator liest ihn aus seiner Konfiguration
 * (`keycloak-setup.*`); die Naht erlaubt später eine andere Quelle.
 */
fun interface KeycloakSetupSource {
    fun variant(name: String): KeycloakSetup
}

/**
 * Die feste Id der Nutzer-Federation. Keycloak bildet daraus `f:<diese Id>:<accountId>` und damit das
 * `sub` jedes Tokens; eine bei jedem Aufbau neue Id würde alle `sub`, Zustimmungen und Sitzungen
 * ändern. Der Orchestrator rechnet mit derselben Konstante.
 */
const val USER_STORAGE_COMPONENT_ID = "orch-accounts"

/** Die Keycloak-Id des föderierten Nutzers zu [accountId] (`StorageId` in Keycloak). */
fun federatedUserId(accountId: Long): String = "f:$USER_STORAGE_COMPONENT_ID:$accountId"

/** Die Umkehrung von [federatedUserId]; `null` für einen Nutzer, der nicht aus der Federation stammt. */
fun accountIdOfFederatedUser(userId: String): Long? =
    userId.removePrefix("f:$USER_STORAGE_COMPONENT_ID:").takeIf { it != userId }?.toLongOrNull()

/**
 * Die feste Id der zweiten Nutzer-Federation, der Einladungen (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
 * Ihre Nutzer heißen `f:orch-invitations:<Hash des Einmalkennworts>` und teilen sich nie ein `sub`
 * mit einem Konto, auch nicht mit dem Konto derselben Person.
 */
const val INVITATION_STORAGE_COMPONENT_ID = "orch-invitations"

/** Die Keycloak-Id des Nutzers einer Einladung. */
fun federatedInvitationUserId(invitation: String): String = "f:$INVITATION_STORAGE_COMPONENT_ID:$invitation"
