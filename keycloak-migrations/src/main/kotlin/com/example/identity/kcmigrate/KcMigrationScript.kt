package com.example.identity.kcmigrate

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * Kompilierungs-Konfiguration für .kc.kts-Dateien: Die Skripte bekommen den Klassenpfad dieses
 * Moduls, und defaultImports macht die *Representation-Klassen ohne Import-Zeile verfügbar.
 */
object KcMigrationScriptConfig : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        "org.keycloak.representations.idm.*",
        "org.keycloak.representations.userprofile.config.*",
        "org.keycloak.common.util.MultivaluedHashMap",
        "com.example.identity.kcmigrate.*",
    )
})

/** Empfänger von step("...") { } - up/down werden hier verschachtelt, nicht über einen Namen gematcht. */
class KcMigrationStep {
    internal var upBlock: (StepContext.() -> Unit)? = null
    internal var downBlock: (StepContext.() -> Unit)? = null

    fun up(block: StepContext.() -> Unit) {
        upBlock = block
    }

    fun down(block: StepContext.() -> Unit) {
        downBlock = block
    }
}

internal class ResolvedStep(
    val name: String,
    val up: StepContext.() -> Unit,
    val down: (StepContext.() -> Unit)?,
)

/**
 * Implizite Basisklasse jeder V<n>__beschreibung.kc.kts-Datei. Ein Skript besteht aus
 * step("...") { up { } down { } }-Blöcken; up/down gehören durch die Verschachtelung zusammen, der
 * Name ist nur ein Label. Ohne down { } ist die Migration ab diesem Schritt nicht automatisch
 * zurückrollbar. Reihenfolge und Fortsetzen nach Abbruch regelt [MigrationRunner].
 */
@KotlinScript(
    fileExtension = "kc.kts",
    compilationConfiguration = KcMigrationScriptConfig::class,
)
abstract class KcMigrationScript {
    internal val steps = mutableListOf<ResolvedStep>()

    fun step(name: String, configure: KcMigrationStep.() -> Unit) {
        val built = KcMigrationStep().apply(configure)
        val upBlock = built.upBlock ?: error("step(\"$name\"): kein up { } definiert")
        steps.add(ResolvedStep(name, upBlock, built.downBlock))
    }
}
