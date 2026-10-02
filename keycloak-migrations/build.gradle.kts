import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "com.example.identity"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // api statt implementation: org.keycloak.admin.client.Keycloak taucht in der öffentlichen
    // Signatur von buildAdminClient()/MigrationRunner auf - Konsumenten (root-Projekt) müssen den
    // Typ also auf ihrem eigenen Compile-Classpath sehen.
    api(libs.keycloak.admin.client)
    // JVM scripting host: kompiliert und evaluiert die .kc.kts-Migrationsdateien zur Laufzeit,
    // mit KcMigrationScript als impliziter Basisklasse (siehe KcMigrationScript.kt) - dadurch
    // sehen die Skripte die DSL-Funktionen (up/down, KcContext) ohne eigene Imports.
    // kotlin-scripting-jvm-host deklariert seine kotlin.script.experimental.* Abhängigkeiten in
    // der POM nur mit scope=runtime - ohne die drei hier explizit auf implementation zu heben,
    // fehlen sie auf dem Compile-Classpath (ResultValue, ScriptCompilationConfiguration, ...).
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.script.runtime)
    // KeycloakSetup leitet Namen, Werte und Overrides seiner Felder aus dem primaeren
    // Konstruktor ab - ein neues Feld ist dadurch genau eine Zeile, ohne parallel gepflegte
    // Namensliste. Explizit auf die Kotlin-Version: transitiv zoege keycloak-admin-client sonst ein
    // deutlich aelteres kotlin-reflect herein, das nicht zu dieser Stdlib passt.
    implementation(libs.kotlin.reflect)
}
