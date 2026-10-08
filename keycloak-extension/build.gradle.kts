plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.cyclonedx)
}

group = "com.example.identity"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // Provided by the Keycloak runtime - not shaded into the provider jar.
    compileOnly(libs.keycloak.server.spi)
    compileOnly(libs.keycloak.server.spi.private)
    compileOnly(libs.keycloak.services)
    // AbstractUserAdapterFederatedStorage - der Adapter fuer foederierte Nutzer ohne Import (ADR-38).
    compileOnly(libs.keycloak.model.storage)
    compileOnly(libs.keycloak.model.storage.private)

    // Same JOSE/JWT library AND version the orchestrator's PeerAuthValidator uses - one entry in
    // the version catalog, so ES256 signing on this side and verification on the orchestrator side
    // cannot drift apart. Not provided by Keycloak's runtime, so it has to be shaded into the
    // provider jar.
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.jackson2.databind)
    // Begleiter fuer java.time, den die generierten Vertragsmodelle brauchen (der Vertrag fuehrt
    // date-time-Felder). Dieselbe Jackson-Version, damit nichts auseinanderlaeuft.
    implementation(libs.jackson2.datatype.jsr310)
    // Der Generator schreibt @javax.annotation.Nonnull an jedes Pflichtfeld. compileOnly, weil die
    // Annotation CLASS-Retention hat: der Compiler braucht sie, die Laufzeit nicht - so bleibt sie
    // aus dem Shadow-Jar heraus.
    compileOnly(libs.jsr305)
    // QR encoding for auth-qr/auth-qr-lookup's WebToolRenderer - core only, no `javase` artifact:
    // the BitMatrix -> PNG conversion is small enough to write directly (QrImageEncoder) without
    // pulling in its extra dependencies.
    implementation(libs.zxing.core)

    // Nur fuer Tests, die ein ComponentModel in die Hand nehmen (OrchestratorSettingsTest) - zur
    // Laufzeit stellt Keycloak diese Klassen, siehe compileOnly oben.
    testImplementation(libs.keycloak.server.spi)
    testImplementation(libs.keycloak.server.spi.private)
    testImplementation(libs.keycloak.model.storage)
    testImplementation(libs.keycloak.model.storage.private)
    testImplementation(libs.keycloak.common)
    testImplementation(libs.keycloak.core)
    // AccountTokenSessionTest: der Grant selbst und Keycloaks Gueltigkeitspruefung der Sitzung
    // (AuthenticationManager.isSessionValid). Ohne Transitive, die Tests brauchen keinen Server.
    testImplementation(libs.keycloak.services) { isTransitive = false }
    // Die Fehlerantwort des Grants ist eine WebApplicationException; sie braucht eine JAX-RS-Laufzeit.
    testRuntimeOnly(libs.resteasy.core)
    // OrchestratorNextDispatchTest: MultivaluedMap, zur Laufzeit von Keycloak gestellt (RESTEasy).
    testImplementation(libs.jakarta.ws.rs.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // KcTextCatalog: eigene Nutzertexte aus den kompilierten Klassen einsammeln (docs/adr/ADR-033).
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.analysis)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Getypte Modelle des Orchestrator-Vertrags, erzeugt aus api/openapi.yaml.
 *
 * Vorher las OrchestratorClient den Vertrag von Hand: `json.path("channel").path("state")
 * .asText(null)`. Das ist der schlechteste Fehlermodus - ein umbenanntes Feld liefert `null` statt
 * einer Exception, und der Fehler taucht drei Schichten spaeter auf. Jetzt faellt er beim
 * Compilieren auf.
 *
 * BEWUSST KEINE Task-Kante zum Snapshot-Test des Hauptprojekts. Die haette zwei Probleme: sie
 * schloesse einen Zyklus (stageKeycloakArtifact haengt bereits an :keycloak-extension:shadowJar),
 * und der Test-Classpath des Hauptprojekts zieht ueber processResources den npm-Build mit - genau
 * die Verklemmung, die in dessen build.gradle.kts fuer generateFrontendApiTypes schon dokumentiert
 * ist. api/openapi.yaml ist eingecheckt und damit schlicht eine Eingabedatei.
 *
 * Die Kette schliesst sich trotzdem, ueber zwei getrennte Waechter:
 *   checkOpenApiSnapshot            sichert  Code -> YAML
 *   :keycloak-extension:compileJava sichert  YAML -> Extension
 */
val generateOrchestratorModels = tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generateOrchestratorModels") {
    group = "build"
    description = "Erzeugt die Vertragsmodelle aus api/openapi.yaml."
    generatorName.set("java")
    inputSpec.set(rootProject.layout.projectDirectory.file("api/openapi.yaml").asFile.absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    modelPackage.set("com.example.identity.kcext.api.model")
    globalProperties.set(mapOf("models" to "", "supportingFiles" to ""))
    generateModelDocumentation.set(false)
    generateApiDocumentation.set(false)
    generateModelTests.set(false)
    generateApiTests.set(false)
    configOptions.set(
        mapOf(
            // Jackson 2, wie es dieses Modul ohnehin shaded - so kommt KEINE Abhaengigkeit dazu.
            // (Das Hauptprojekt laeuft auf Jackson 3 / tools.jackson; ein weiterer Grund, warum das
            // Modul eigene Modelle braucht und nicht die Kotlin-DTOs teilen koennte.)
            "serializationLibrary" to "jackson",
            "library" to "native",
            // Die vier verhindern, dass der Generator neue Jars in den Shadow-Jar zieht:
            "annotationLibrary" to "none",
            "documentationProvider" to "none",
            "useBeanValidation" to "false",
            "openApiNullable" to "false",
            // Sonst ist das Ergebnis nie reproduzierbar.
            "hideGenerationTimestamp" to "true",
            "dateLibrary" to "java8"
        )
    )
    doFirst { delete(layout.buildDirectory.dir("generated/openapi")) }
    // ApiClient und JSON rufen die in Jackson 2.20 veraltete serializationInclusion auf; der
    // Generator kennt keinen Ersatz. Die Unterdrueckung bleibt auf den erzeugten Code beschraenkt.
    doLast {
        val apiDir = layout.buildDirectory.dir("generated/openapi/src/main/java/com/example/identity/kcext/api").get().asFile
        listOf("ApiClient", "JSON").forEach { name ->
            val file = apiDir.resolve("$name.java")
            val declaration = "public class $name {"
            check(declaration in file.readText()) { "$declaration fehlt in $file" }
            file.writeText(file.readText().replace(declaration, "@SuppressWarnings(\"deprecation\")\n$declaration"))
        }
    }
}

sourceSets["main"].java.srcDir(generateOrchestratorModels.map { layout.buildDirectory.dir("generated/openapi/src/main/java") })

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("identity-demo-keycloak-extension")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Quellkatalog der eigenen Texte (docs/adr/ADR-033) fuer /translate-texts - Java-Vorlagen (KcText.t,
// KcTexts.of) und Template-Vorlagen (t.of("...") in den .ftl). Landet beim Wurzelprojekt unter
// build/texts/keycloak/, neben den Katalogen von Backend und Frontend.
tasks.register<JavaExec>("exportTexts") {
    group = "texts"
    description = "Schreibt die Text-Vorlagen der Extension nach build/texts/keycloak/texts_source.properties (Wurzelprojekt)."
    dependsOn("testClasses", ":exportKeycloakThemeTexts")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.example.identity.kcext.client.KcTextCatalog")
    systemProperty("texts.keycloakThemeCatalog", rootProject.file("keycloak-theme/build/texts-catalog.json").absolutePath)
    args(
        layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath,
        layout.projectDirectory.dir("src/main/resources/theme").asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("texts").get().asFile.absolutePath
    )
}

// toolNamesAreTheAppsOwn vergleicht mit dem Frontend-Katalog (docs/adr/ADR-033).
tasks.named<Test>("test") {
    dependsOn(":exportFrontendTexts", ":exportKeycloakThemeTexts")
    // KcTextCatalogTest: die Vorlagen des Keycloakify-Themes gehoeren ins selbe Bundle.
    systemProperty("texts.keycloakThemeCatalog", rootProject.file("keycloak-theme/build/texts-catalog.json").absolutePath)
    systemProperty("texts.frontendCatalog", rootProject.file("frontend/build/texts-catalog.json").absolutePath)
}
