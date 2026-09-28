package com.example.identity.kcmigrate

import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource

/**
 * Empfänger von up { } / down { } in einem step(...)-Block. Implementiert RealmResource per
 * Delegation, plus remember/recall für Daten, die up erzeugt und down braucht (z.B. eine generierte
 * ID); sie stehen sofort im Realm-Attribut des Schritts und überstehen einen neuen Prozesslauf.
 * setup trägt alles Dynamische ([RealmSetup]): Ein Skript liest keine Umgebungsvariable und schreibt
 * keinen Host, Port oder Client-Id selbst hin.
 */
class StepContext internal constructor(
    val kc: Keycloak,
    val setup: RealmSetup,
    realm: RealmResource,
    private val memory: StepMemory,
) : RealmResource by realm {
    val realmName: String get() = setup.realmName

    fun remember(key: String, value: String) = memory.remember(key, value)
    fun recall(key: String): String = memory.recall(key)
    fun recallOrNull(key: String): String? = memory.recallOrNull(key)
}

internal class StepMemory(
    private val readAttr: (String) -> String?,
    private val writeAttr: (String, String) -> Unit,
    private val keyFor: (String) -> String,
) {
    fun remember(key: String, value: String) = writeAttr(keyFor(key), value)

    fun recall(key: String): String = recallOrNull(key)
        ?: error("Kein gemerkter Wert für \"$key\" - wurde der zugehörige up-Schritt schon erfolgreich ausgeführt?")

    fun recallOrNull(key: String): String? = readAttr(keyFor(key))
}
