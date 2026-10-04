import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Delete
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    kotlin("plugin.jpa") version "2.4.20"
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
    alias(libs.plugins.kover)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.cyclonedx)
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

// Mock/demo-only code is deliberately not held to the same coverage bar as production logic:
// personenverzeichnis simulates the external Personenverzeichnis this demo has no real access to, ident_eid
// is a "Mock eID" standing in for real AusweisApp/card-reader hardware (see its own Descriptors.kt
// doc comment), and DemoAutoPickNote/DemoInfo/JourneyDebugStep exist purely for the frontend's
// "Struktur" debug view (docs/05-api.md #2, `demo`) - counting them would understate real
// coverage where it matters and overstate it where a gap is harmless.
kover {
    // Kover haengt jede Test-Task an koverVerify und damit an `build`. Fuer die beiden
    // Snapshot-Tasks ist das falsch: updateOpenApiSnapshot wuerde bei jedem Build api/openapi.yaml
    // stillschweigend neu schreiben - und damit genau den Unterschied glaetten, den
    // checkOpenApiSnapshot melden soll. Beide laufen nur, wenn man sie beim Namen ruft. quickTest
    // ist eine Teilmenge von test und wuerde `build` nur verdoppeln; seine Abdeckung zaehlt nicht.
    currentProject {
        instrumentation {
            disabledForTestTasks.addAll("checkOpenApiSnapshot", "updateOpenApiSnapshot", "quickTest")
        }
    }
    reports {
        // Eine Sperrklinke, kein Qualitaetsziel: der Wert liegt knapp unter dem heutigen Stand
        // (84 %, Zeilen), damit koverVerify bei einem spuerbaren Rueckgang den Build bricht statt
        // nur einen Bericht zu schreiben. Steigt die Abdeckung, wird die Grenze nachgezogen - nie
        // gesenkt, um eine Aenderung durchzubekommen.
        verify {
            rule {
                minBound(82)
            }
        }
        filters {
            excludes {
                classes(
                    "com.example.identity.simulation.personenverzeichnis.*",
                    "com.example.identity.simulation.personenverzeichnis.internal.*",
                    "com.example.identity.tools.ident_eid.*",
                    "com.example.identity.tools.ident_eid.internal.*",
                    "com.example.identity.contract.tool_api.envelope.DemoInfo",
                    "com.example.identity.contract.tool_api.envelope.JourneyDebugStep"
                )
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// kotlin("plugin.jpa")'s own default annotation preset isn't opening these jakarta.persistence
// entities in practice (verified via javap: getters/classes came out final) - configuring the
// allOpen extension it brings in explicitly is the documented fix so Hibernate can proxy them
// for lazy loading.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":keycloak-migrations"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.data.jpa)
    // Guards only the operator endpoints (/orchestrator/admin/**, AdminSecurityConfig) - DPoP and
    // the Keycloak facade authenticate themselves and stay outside Spring Security.
    implementation(libs.spring.boot.starter.security)
    // Health (Probes) und Kennzahlen auf einem eigenen Management-Port, nie ueber die oeffentliche
    // Route (docs/07-betrieb.md Abschnitt 7).
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.modulith.starter.core)
    // Event Publication Registry: Spring Modulith' eigener transaktionaler Outbox
    // (docs/07-betrieb.md Abschnitt 3a). Bringt events-api/-core/-jpa/-jackson mit.
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.jackson2.module.kotlin)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    runtimeOnly(libs.h2)
    // Spring Boot 4's modular autoconfigure split the H2 console out of the core autoconfigure
    // jar into its own module - without this it silently 404s even with spring.h2.console.enabled=true.
    runtimeOnly(libs.spring.boot.h2console)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.httpclient5)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    // TextCatalog: Nutzertexte aus den kompilierten Klassen einsammeln (docs/adr/ADR-033).
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.analysis)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// SBOM nur aus dem, was ausgeliefert wird: Testbibliotheken pruefen wir nicht gegen OSV (CI, ci.yml).
allprojects {
    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("runtimeClasspath"))
    }
}

// Ueberschreibt Spring Boots verwaltete Versionen (libs.versions.toml, Kommentar bei tomcat).
extra["tomcat.version"] = libs.versions.tomcat.get()
extra["jackson-bom.version"] = libs.versions.jackson3.get()
extra["jackson-2-bom.version"] = libs.versions.jackson2.boot.get()

dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
    }
}

val frontendDir = file("frontend")

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "frontend"
    description = "Installs frontend dependencies"
    workingDir = frontendDir
    inputs.file(frontendDir.resolve("package.json"))
    outputs.dir(frontendDir.resolve("node_modules"))
    commandLine("npm", "install")
}

val npmBuild = tasks.register<Exec>("npmBuild") {
    group = "frontend"
    description = "Builds the frontend and copies it to src/main/resources/static"
    dependsOn(npmInstall)
    workingDir = frontendDir
    inputs.dir(frontendDir.resolve("src"))
    // One HTML entry per page app (vite.config.ts `input`) - a changed page title must rebuild too.
    inputs.files(fileTree(frontendDir) { include("index.html", "*/index.html"); exclude("node_modules/**") })
    inputs.file(frontendDir.resolve("vite.config.ts"))
    inputs.file(frontendDir.resolve("package.json"))
    outputs.dir(file("src/main/resources/static"))
    commandLine("npm", "run", "build")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(npmBuild)
}

// Das Keycloakify-Theme neben dem FreeMarker-Theme (docs/adr/ADR-041-keycloakify-neben-freemarker.md):
// eigenes npm-Paket, Ergebnis ist ein Theme-JAR fuer /opt/keycloak/providers. keycloakify build
// packt das JAR mit Maven - mvn muss auf dem PATH liegen.
val keycloakThemeDir = file("keycloak-theme")
val freemarkerThemeDir = file("keycloak-extension/src/main/resources/theme/orchestrator/login")

val keycloakThemeNpmInstall = tasks.register<Exec>("keycloakThemeNpmInstall") {
    group = "keycloak theme"
    description = "Installs the Keycloakify theme's dependencies"
    workingDir = keycloakThemeDir
    inputs.file(keycloakThemeDir.resolve("package.json"))
    outputs.dir(keycloakThemeDir.resolve("node_modules"))
    commandLine("npm", "install")
}

val keycloakThemeBuild = tasks.register<Exec>("keycloakThemeBuild") {
    group = "keycloak theme"
    description = "Builds the Keycloakify theme JAR (keycloak-theme/dist_keycloak)"
    dependsOn(keycloakThemeNpmInstall)
    workingDir = keycloakThemeDir
    inputs.dir(keycloakThemeDir.resolve("src"))
    // scripts/texts-per-page.mjs schreibt beim Build, welche Texte jede Seite braucht.
    inputs.dir(keycloakThemeDir.resolve("scripts"))
    inputs.files(keycloakThemeDir.resolve("index.html"), keycloakThemeDir.resolve("vite.config.ts"), keycloakThemeDir.resolve("package.json"))
    // Gebuendelt aus dem FreeMarker-Theme: die gemeinsamen Tokens. Die Texte nicht - die setzt die
    // Extension zur Laufzeit in jede Seite (kcContext.texts).
    inputs.file(freemarkerThemeDir.resolve("resources/css/tokens.css"))
    outputs.file(keycloakThemeDir.resolve("dist_keycloak/orchestrator-keycloakify-theme.jar"))
    commandLine("npm", "run", "build-keycloak-theme")
}

// Die Vorlagen des Keycloakify-Themes (t("...") in keycloak-theme/src) fuer den Katalog des Bundles
// keycloak - KcTextCatalog der Extension liest sie neben den .ftl-Vorlagen.
val keycloakThemeTextCatalog = keycloakThemeDir.resolve("build/texts-catalog.json")
tasks.register<Exec>("exportKeycloakThemeTexts") {
    group = "texts"
    description = "Schreibt die Text-Vorlagen des Keycloakify-Themes nach keycloak-theme/build/texts-catalog.json."
    dependsOn(keycloakThemeNpmInstall)
    workingDir = keycloakThemeDir
    inputs.dir(keycloakThemeDir.resolve("src"))
    inputs.dir(keycloakThemeDir.resolve("scripts"))
    outputs.file(keycloakThemeTextCatalog)
    commandLine("npm", "run", "texts:export", "--", keycloakThemeTextCatalog.absolutePath)
}

// Die eigenen Texte des Frontends (docs/adr/ADR-033): frontend/scripts/text-catalog.mjs liest jede
// t("...")/<Tx text="...">-Vorlage aus dem geparsten Quelltext. TextTranslationsTest und exportTexts
// fuehren sie mit den Backend-Vorlagen zu einem Katalog je Bundle zusammen.
val frontendTextCatalog = frontendDir.resolve("build/texts-catalog.json")
val exportFrontendTexts = tasks.register<Exec>("exportFrontendTexts") {
    group = "texts"
    description = "Schreibt die Text-Vorlagen des Frontends nach frontend/build/texts-catalog.json."
    dependsOn(npmInstall)
    workingDir = frontendDir
    inputs.dir(frontendDir.resolve("src"))
    inputs.file(frontendDir.resolve("scripts/text-catalog.mjs"))
    outputs.file(frontendTextCatalog)
    commandLine("npm", "run", "texts:export", "--", frontendTextCatalog.absolutePath)
}

tasks.named<Test>("test") {
    dependsOn(exportFrontendTexts)
    inputs.file(frontendTextCatalog)
    systemProperty("texts.frontendCatalog", frontendTextCatalog.absolutePath)
    // ModelBasedJourneyTest: Zahl der Zufallsfolgen, z. B. -PmodelSeeds=1000 fuer eine gruendliche Suche.
    providers.gradleProperty("modelSeeds").orNull?.let { systemProperty("model.seeds", it) }
}

// `./gradlew quickTest` - die Suite ohne jeden Spec, der einen Spring-Kontext startet
// (io.kotest.provided.TestTier): fuer den Zwischenstand beim Entwickeln. `test` und `check`
// bleiben der vollstaendige Lauf.
tasks.register<Test>("quickTest") {
    group = "verification"
    description = "Fuehrt die Tests ohne Spring-Kontext aus (Unit- und Architekturtests)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn(exportFrontendTexts)
    inputs.file(frontendTextCatalog)
    systemProperty("texts.frontendCatalog", frontendTextCatalog.absolutePath)
    systemProperty("test.tier", "quick")
}

tasks.named<Delete>("clean") {
    delete(file("src/main/resources/static"))
}

// `./gradlew bootRunKc` - same as bootRun, just with the `keycloak` profile active (real Keycloak
// via podman compose, see compose.yml/docs/05-api.md Abschnitt 3; without it there is no Web
// channel at all). Equivalent to `bootRun --args='--spring.profiles.active=keycloak'`,
// just without having to remember/retype that every time.
tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("bootRunKc") {
    group = "application"
    description = "Runs the app with the 'keycloak' Spring profile active (real Keycloak via podman compose)."
    classpath = sourceSets["main"].runtimeClasspath
    // Hardcoded rather than derived from the "bootRun" task's own resolved mainClass: reading a
    // Provider off another task creates an implicit Gradle task dependency, which for "bootRun"
    // itself means actually EXECUTING it (launching the app under the default profile) as a
    // "configuration" step - it blocks, so bootRunKc would never even start.
    mainClass.set("com.example.identity.IdentityApplicationKt")
    systemProperty("spring.profiles.active", "keycloak")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Fehlende Uebersetzungen sind beim Entwickeln nur eine Warnung (der Client zeigt so lange die
    // Vorlage); fuer einen Build ausserhalb des Demomodus ./gradlew test -PstrictTexts (ADR-33).
    val strictTexts = providers.gradleProperty("strictTexts").isPresent
    systemProperty("texts.strict", strictTexts)
    inputs.property("strictTexts", strictTexts)
    // Der eingecheckte Vertrag ist eine Eingabe der Tests, nicht nur ihre Ausgabe:
    // DiscriminatorMappingTest liest api/openapi.yaml direkt. Ohne diese Zeile haelt Gradle die
    // Tests fuer aktuell, wenn sich nur der Vertrag geaendert hat - der Test laeuft dann nicht und
    // meldet folgerichtig auch nichts.
    inputs.dir(layout.projectDirectory.dir("api")).withPropertyName("apiContract")
    // Dasselbe fuer das Invariantenregister: InvariantRegisterTest liest docs/invarianten.md direkt.
    inputs.file(layout.projectDirectory.file("docs/invarianten.md")).withPropertyName("invariantRegister")
    // Und fuer die Journey-Diagramme: JourneyDiagramsTest liest docs/journeys/ direkt - ohne diese
    // Zeile liefe er nach einer reinen Doku-Aenderung nicht.
    inputs.dir(layout.projectDirectory.dir("docs/journeys")).withPropertyName("journeyDocs")
    // Die Test-JVMs haengen Agenten an den Bootclasspath (Kover, ByteBuddy). Class Data Sharing
    // bricht dann ab und meldet "Sharing is only supported for boot loader classes" bei jedem
    // Start - aus, statt jedes Mal zu warnen.
    jvmArgs("-Xshare:off")
    // Gradles Default fuer Test-JVMs sind 512 MB. Die Suite haelt mehrere Spring-Kontexte im
    // Cache (je MockkBean-Kombination einer), und mit Spring Security wurde jeder etwas groesser -
    // der ArchUnit-Import am Ende lief dann in "OutOfMemoryError: Java heap space".
    maxHeapSize = "1g"
}

// Der API-Vertrag hat drei Leser (Kotlin-DTOs, Frontend, keycloak-extension); beide Clients werden
// aus api/openapi.yaml generiert. Das ist die eine Stelle, an der er steht; OpenApiSnapshotTest
// prueft ihn gegen den laufenden Code. `check` laeuft ohnehin ueber `test` mit - diese beiden
// Tasks sind nur die benannten Ein- und Ausgaenge dafuer.
val checkOpenApiSnapshot = tasks.register<Test>("checkOpenApiSnapshot") {
    group = "verification"
    description = "Prueft api/openapi.yaml gegen die Spec des laufenden Codes."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.example.identity.core.orchestrator.api.v1.OpenApiSnapshotTest") }
    // Ein Vertrags-Diff ist kein flaky Test: immer neu ausfuehren, nie aus dem Build-Cache
    // als "up to date" ueberspringen, sonst geht genau die Aenderung durch, die er fangen soll.
    outputs.upToDateWhen { false }
}

tasks.register<Test>("updateOpenApiSnapshot") {
    group = "verification"
    description = "Schreibt api/openapi.yaml aus der Spec des laufenden Codes neu."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.example.identity.core.orchestrator.api.v1.OpenApiSnapshotTest") }
    systemProperty("openapi.snapshot.update", "true")
    outputs.upToDateWhen { false }
}

// Quellkatalog der Nutzertexte fuer /translate-texts (docs/adr/ADR-033): TextCatalog liest die
// Text-Vorlagen aus den kompilierten Klassen und schreibt sie je Bundle nach build/texts/. Wird nie
// ausgeliefert - die Clients bekommen die uebersetzten Dateien unter src/main/resources/texts/.
tasks.register<JavaExec>("exportTexts") {
    group = "texts"
    description = "Schreibt die Text-Vorlagen aus dem Code nach build/texts/<bundle>/texts_source.properties."
    dependsOn("testClasses", exportFrontendTexts, ":keycloak-extension:exportTexts")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.example.identity.contract.texts.TextCatalogExportKt")
    systemProperty("texts.frontendCatalog", frontendTextCatalog.absolutePath)
    args(layout.buildDirectory.dir("texts").get().asFile.absolutePath)
}

// Der Wachposten fuer bereits veroeffentlichte Versionen (docs/05-api.md, ADR-50).
//
// Eingefroren ist nicht api/openapi.yaml als Ganzes, sondern ihre Teile, die OpenApiSnapshotTest
// unter api/contract/ erzeugt: der Umschlag (envelope.yaml) und je Tool eine Datei. Ein Bruch am
// Umschlag braucht eine neue API-Version. Ein Bruch an einem Tool trifft nur Clients, die es in
// availableTools nennen; man aendert es additiv (eine Tool-Version sieht das Modell noch nicht vor). /kc/** ist in keinem Teil: das ruft
// nur die Keycloak-Erweiterung, die mit dem Server ausgeliefert wird.
//
// api/published/ ist der Stand zum Zeitpunkt der Veroeffentlichung und wird NICHT neu erzeugt.
// OpenApiSnapshotTest sichert "Code passt zu api/contract/"; dieser Vergleich sichert "api/contract/
// bricht nichts Veroeffentlichtes".
//
// Eigene Konfiguration statt testImplementation: openapi-diff bringt swagger-parser mit, das mit
// dem swagger-core von springdoc kollidiert, und jcl-over-slf4j, das mit Boots Logging kollidiert.
val openapiDiff: Configuration = configurations.create("openapiDiff")

dependencies {
    openapiDiff(variantOf(libs.openapi.diff.cli) { classifier("all") })
}

/**
 * Vergleicht jeden eingefrorenen Teil mit seinem aktuellen Gegenstueck. Ein neues Tool hat noch
 * keinen eingefrorenen Stand und ist kein Bruch; ein entferntes Tool auch nicht - Clients, die es
 * nennen, bekommen es nur nicht mehr angeboten. Beides meldet der Task als Hinweis.
 */
abstract class CheckPublishedApiCompatibility @Inject constructor(
    private val exec: ExecOperations
) : DefaultTask() {
    @get:Classpath
    abstract val diffClasspath: ConfigurableFileCollection

    @get:InputDirectory
    abstract val published: DirectoryProperty

    @get:InputDirectory
    abstract val current: DirectoryProperty

    @TaskAction
    fun check() {
        val publishedDir = published.get().asFile
        val currentDir = current.get().asFile
        val envelope = "envelope.yaml"
        val tools = "tools"
        val failures = mutableListOf<String>()

        fun compare(old: File, new: File, onBreak: String) {
            val output = ByteArrayOutputStream()
            val result = exec.javaexec {
                classpath = diffClasspath
                mainClass.set("org.openapitools.openapidiff.cli.Main")
                args(old.absolutePath, new.absolutePath, "--fail-on-incompatible")
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) failures += "$onBreak\n${output.toString(Charsets.UTF_8).trim()}"
        }

        compare(publishedDir.resolve("v1/$envelope"), currentDir.resolve(envelope),
            "Bruch am Umschlag (${currentDir.name}/$envelope): braucht eine neue API-Version.")

        val publishedTools = publishedDir.resolve(tools).listFiles().orEmpty().filter { it.extension == "yaml" }
        val currentTools = currentDir.resolve(tools).listFiles().orEmpty().filter { it.extension == "yaml" }
        publishedTools.sortedBy { it.name }.forEach { old ->
            val toolId = old.nameWithoutExtension
            val new = currentDir.resolve("$tools/${old.name}")
            if (!new.exists()) {
                logger.lifecycle("Hinweis: Tool $toolId ist entfallen. Clients, die es nennen, bekommen es nicht mehr angeboten.")
            } else {
                compare(old, new, "Bruch an $toolId: additiv aendern; geht das nicht, braucht es eine Tool-Version (noch nicht vorgesehen, ADR-50).")
            }
        }
        val newTools = currentTools.map { it.nameWithoutExtension } - publishedTools.map { it.nameWithoutExtension }.toSet()
        newTools.sorted().forEach { logger.lifecycle("Hinweis: Tool $it ist neu und noch nicht eingefroren (publishApiVersion).") }

        if (failures.isNotEmpty()) throw GradleException(failures.joinToString("\n\n"))
    }
}

tasks.register<CheckPublishedApiCompatibility>("checkPublishedApiCompatibility") {
    group = "verification"
    description = "Prueft api/contract/ (Umschlag und je Tool) gegen den eingefrorenen Stand unter api/published/."
    diffClasspath.from(openapiDiff)
    published.set(layout.projectDirectory.dir("api/published"))
    current.set(layout.projectDirectory.dir("api/contract"))
    mustRunAfter("publishApiVersion")
    // Wie bei checkOpenApiSnapshot: ein Vertragsbruch darf nie als "up to date" durchgehen.
    outputs.upToDateWhen { false }
}

// Der einzige legitime Weg, die eingefrorene Fassung zu aendern. Ihr Diff in einem PR ist das
// Signal "hier wird eine veroeffentlichte Version angefasst". Sync statt Copy: ein entferntes
// Tool verschwindet auch aus dem eingefrorenen Stand.
tasks.register<Sync>("publishApiVersion") {
    group = "verification"
    description = "Hebt api/contract/ zur veroeffentlichten Fassung unter api/published/."
    from(layout.projectDirectory.file("api/contract/envelope.yaml")) { into("v1") }
    from(layout.projectDirectory.dir("api/contract/tools")) { into("tools") }
    into(layout.projectDirectory.dir("api/published"))
}

// Die dritte Seite des Vertrags: die Frontend-Typen werden aus demselben Snapshot erzeugt, statt
// wie bisher in types.ts von Hand nachgepflegt zu werden. Damit wird eine Backend-DTO-Aenderung
// im Frontend zum TypeScript-Fehler statt zu einem `undefined` zur Laufzeit.
//
// Nur Modelle, keine API-Clients: das Frontend ruft ueber seine eigene DPoP-behaftete api.ts auf
// (jede Anfrage braucht einen frisch signierten Proof), ein generierter fetch-Client koennte das
// nicht und wuerde nur ungenutzt mitlaufen.
val generateFrontendApiTypes = tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generateFrontendApiTypes") {
    group = "frontend"
    description = "Erzeugt frontend/src/generated aus api/openapi.yaml (OpenAPI Generator)."
    generatorName.set("typescript-fetch")
    inputSpec.set(layout.projectDirectory.file("api/openapi.yaml").asFile.absolutePath)
    outputDir.set(layout.projectDirectory.dir("frontend/src/generated").asFile.absolutePath)
    // typescript-fetch buendelt alle Modelle in models/index.ts und fuehrt diese Datei als
    // "supporting file" - ohne den zweiten Eintrag erzeugt der Generator nur die .md-Doku und
    // keine einzige .ts-Datei.
    globalProperties.set(mapOf("models" to "", "supportingFiles" to "index.ts"))
    // Die .md-Doku dupliziert nur, was schon im Snapshot und in den Doc-Kommentaren steht.
    generateModelDocumentation.set(false)
    configOptions.set(
        mapOf(
            // Die erzeugten Interfaces sollen genau die Wire-Namen tragen, damit ein Feld im
            // Frontend so heisst wie im Kotlin-DTO und im Snapshot - sonst waere der Abgleich
            // wieder eine Uebersetzungsleistung von Hand.
            "modelPropertyNaming" to "original",
            "enumPropertyNaming" to "original",
            "supportsES6" to "true",
            "withoutRuntimeChecks" to "true"
        )
    )
    // Der Generator raeumt sein Ausgabeverzeichnis nicht selbst auf: ein geloeschtes DTO liesse
    // sonst seine Datei zurueck und das Frontend koennte weiter dagegen compilieren.
    doFirst { delete(layout.projectDirectory.dir("frontend/src/generated")) }
    // Das oberste index.ts re-exportiert `./runtime` - den fetch-Client, den wir bewusst nicht
    // erzeugen (siehe oben). Die Datei wuerde den Typcheck des Frontends brechen; importiert wird
    // ausschliesslich ./generated/models.
    doLast { delete(layout.projectDirectory.file("frontend/src/generated/index.ts")) }
}

// Bewusst KEIN `npmBuild.dependsOn(generateFrontendApiTypes)`: der Test-Runtime-Classpath zieht
// processResources und damit npmBuild mit, also haenge der Snapshot-Task sonst an einem
// Generatorlauf ueber genau den Snapshot, den er gerade erst schreiben soll - bei ungueltiger Spec
// blockiert sich das gegenseitig. Stattdessen ist frontend/src/generated eingecheckt und die CI
// prueft per `git diff --exit-code`, dass es zum Snapshot passt.
//
// Wohl aber eine Reihenfolge: stehen beide im selben Aufruf (`./gradlew build
// generateFrontendApiTypes`), liest npmBuild das Verzeichnis, das der Generator gerade schreibt, und
// Gradle bricht mit "implicit dependency" ab. mustRunAfter zieht den Generator nicht in einen Lauf,
// der ihn nicht verlangt - die Blockade oben entsteht also nicht -, sorgt aber dafuer, dass npmBuild
// die frischen Typen sieht, wenn er mitlaeuft.
npmBuild { mustRunAfter(generateFrontendApiTypes) }

// Trennt Gradle-Build von Podman-Build: die Dockerfiles (mitsamt sich selbst, siehe
// stageOrchestratorDockerfile/stageKeycloakArtifact unten) kopieren nur noch fertige Artefakte aus
// build/podman/*, statt Gradle/npm selbst innerhalb des Containers laufen zu lassen und statt dass
// Podman das gesamte Repo als Build-Kontext einlesen muesste. Muss vor `podman compose
// build`/`up --build` einmal laufen: `./gradlew stagePodmanArtifacts`.
val podmanStageDir = layout.buildDirectory.dir("podman")

val stageOrchestratorArtifact = tasks.register<Exec>("stageOrchestratorArtifact") {
    group = "podman"
    description = "Entpackt den Boot-Jar in einen flachen Classpath unter build/podman/orchestrator."
    dependsOn(tasks.named("bootJar"))
    val destination = podmanStageDir.map { it.dir("orchestrator") }
    val bootJarFile = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar").flatMap { it.archiveFile }
    inputs.file(bootJarFile)
    outputs.dir(destination)
    doFirst {
        destination.get().asFile.deleteRecursively()
    }
    // kotlin-scripting-jvm-host (KeycloakMigrationRunnerStartup) bricht in der gepackten
    // Boot-Fat-Jar ab (siehe Dockerfile-Kommentar) - jarmode=tools extract liefert stattdessen
    // einen klassischen, flachen Classpath (Haupt-Jar + lib/*.jar).
    commandLine(
        "java", "-Djarmode=tools", "-jar", bootJarFile.get().asFile.absolutePath,
        "extract", "--destination", destination.get().asFile.absolutePath,
    )
    // Die extrahierte Haupt-Jar behaelt ihren urspruenglichen Namen (z.B.
    // identity-demo-0.0.1-SNAPSHOT.jar), nicht "app.jar" - explizit umbenennen, weil das Dockerfile
    // per fixem Namen darauf zugreift (ENTRYPOINT -cp 'app.jar:lib/*').
    doLast {
        val destDir = destination.get().asFile
        val extractedJar = destDir.listFiles { f -> f.isFile && f.extension == "jar" }!!.single()
        extractedJar.renameTo(destDir.resolve("app.jar"))
    }
}

// Kopiert das Dockerfile mit nach build/podman/orchestrator, damit compose.yml dieses
// Staging-Verzeichnis (statt des gesamten Repo-Wurzelverzeichnisses) als Build-Kontext angeben
// kann: Podman muss dann nur noch die paar fertigen Artefakte hochladen/hashen, nicht mehr .git,
// node_modules, Gradle-Caches etc. erst durchlaufen. Das Dockerfile selbst nutzt bereits
// kontext-relative COPY-Pfade, keine Umschreibung noetig.
val stageOrchestratorDockerfile = tasks.register<Copy>("stageOrchestratorDockerfile") {
    group = "podman"
    description = "Kopiert das Dockerfile nach build/podman/orchestrator."
    dependsOn(stageOrchestratorArtifact)
    from("Dockerfile")
    into(podmanStageDir.map { it.dir("orchestrator") })
}

val stageKeycloakArtifact = tasks.register<Copy>("stageKeycloakArtifact") {
    group = "podman"
    description = "Kopiert Extension-Shadow-Jar, beide Themes, Healthcheck und Dockerfile nach build/podman/keycloak."
    dependsOn(":keycloak-extension:shadowJar")
    from(project(":keycloak-extension").tasks.named("shadowJar")) {
        rename { "identity-demo-keycloak-extension.jar" }
    }
    from(keycloakThemeBuild)
    from("keycloak-extension/src/main/resources/theme") {
        into("theme")
    }
    from("keycloak-extension/healthcheck/JwksHealthCheck.java") {
        into("healthcheck")
    }
    from("keycloak-extension/Dockerfile")
    into(podmanStageDir.map { it.dir("keycloak") })
}

val stagePodmanArtifacts = tasks.register("stagePodmanArtifacts") {
    group = "podman"
    description = "Baut Orchestrator-Jar, Frontend und Keycloak-Extension und legt beide unter build/podman ab, damit podman compose build/up nur noch fertige Artefakte kopiert."
    dependsOn(stageOrchestratorDockerfile, stageKeycloakArtifact)
}

// Haengt das Staging an den normalen Build-Lifecycle: wer `./gradlew build`/`assemble` laufen
// laesst, bekommt build/podman automatisch aktuell mit - kein separater, leicht zu vergessender
// Handaufruf von stagePodmanArtifacts noetig, bevor `podman compose build`/`up --build` folgt.
// Gradle selbst ruft dabei kein podman compose auf - das bleibt bewusst ein eigener,
// von aussen angestossener Schritt (Podman-Aufrufe brauchen ggf. eine laufende Podman-Machine
// bzw. Rootless-Setup, das ein Gradle-Build nicht voraussetzen sollte).
tasks.named("assemble") {
    dependsOn(stagePodmanArtifacts)
}