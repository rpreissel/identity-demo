# Beispiel für Entwickler: Bank-Ident und Einmalcode-App anbinden

Das Gegenstück zur [Beispiel-Story](11-beispiel-story.md): Dort erlebt eine Nutzerin das System,
hier baut ein Entwickler zwei neue Verfahren ein. Beide sind ausgedacht, aber so gewählt, dass sie
fast jede Stelle berühren, an der ein neues Verfahren andockt:

- **Bank-Ident** (`ident-bank`): Die Person identifiziert sich über ihre Hausbank. Die App leitet zur
  Bank weiter, die Bank prüft die Person mit ihrer eigenen Kundenprüfung, und der Orchestrator holt
  das Ergebnis danach selbst ab. Vorbild ist `ident-nect`.
- **Einmalcode-App** (`enroll-totp`, `auth-totp`): Die Person richtet eine Authenticator-App ein
  (TOTP nach RFC 6238) und meldet sich danach mit dem sechsstelligen Code an. Vorbild sind
  `auth_kobil` (Einrichten und Anmelden) und `auth_sms` (die kleinste Form).

Das Kapitel ist eine Landkarte, keine Bauanleitung Zeile für Zeile. Zu jedem Schritt nennt es die
Datei, von der man abschreibt, und den Test, der meldet, wenn etwas fehlt. Pfade unter `K/` stehen
für `src/main/kotlin/com/example/identity/`.

---

## 1) Zuerst entscheiden: Rollen, Methoden, Niveaus

Ein Verfahren ist ein `ToolModule` mit einem Tool je Rolle (`ToolRole`). Ein Verfahren, das
identifiziert **und** anmeldet, besteht deshalb aus mehreren Tools. Eine Identifizierung richtet
nie ein dauerhaftes Verfahren ein ([03-tool-architektur.md](03-tool-architektur.md) Abschnitt 2).
Hier sind es zwei Module, weil Identifizierung und Anmeldung verschiedene Methoden sind.

| Tool | Rolle | Methode | Faktoren | `maxAcr` | Modul |
|---|---|---|---|---|---|
| `ident-bank` | `IDENTIFICATION` | `bank` | Besitz + Wissen (die starke Kundenauthentifizierung der Bank) | `loa2` | `tools/ident_bank` |
| `enroll-totp` | `ENROLLMENT` | `totp` | Besitz | `loa1` | `tools/auth_totp` |
| `auth-totp` | `KNOWN_ACCOUNT_AUTH` | `totp` | Besitz | `loa1` | `tools/auth_totp` |
| `auth-totp-lookup` (optional) | `ACCOUNT_LOOKUP_AUTH` | `totp` | Besitz | `loa1` | `tools/auth_totp` |

Was dabei zu bedenken ist:

- **Die IDs liegen fest.** `enroll-totp`, `auth-totp` und `auth-totp-lookup` stehen je einmal als
  Konstante im Modul, für Deklaration und Controller. Sie müssen zu Methode `totp` und Rolle
  passen; das Bauen des Moduls prüft es.
- **Faktoren und Niveau gelten für die ganze Methode.** Sie stehen einmal im Modul; ein Tool kann
  nichts anderes angeben.
- **Eigene `amr`-Werte.** `amr` und Methodennamen teilen sich einen Namensraum. `ident-bank` meldet
  deshalb etwa `bank-kyc`, nicht `bank`, so wie `ident-nect` `nect-eid` meldet.
- **Die Methode heißt `totp`, nicht `otp`.** Keycloak bringt ein eigenes OTP mit, das der
  Orchestrator als natives Verfahren kennt (`kc-otp-form` mit der Methode `otp`, siehe
  `NativeAuthenticatorRegistry`). Das neue Tool ist ein anderes Verfahren mit einem anderen
  Geheimnis und braucht deshalb einen anderen Namen.
- **Warum überhaupt ein eigenes TOTP, wenn Keycloak eins hat?** [ADR-8](adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md)
  sagt: Was Keycloak selbst kann, nutzt man. Die App hat aber kein Keycloak vor sich, und dieselbe
  Authenticator-App soll in beiden Kanälen gelten. Ein Geheimnis an einer Stelle ist hier der
  Grund für das eigene Tool. Wer nur den Web-Kanal braucht, nimmt Keycloaks OTP und baut nichts.
- **Niveaus.** TOTP allein beweist Besitz und erreicht `loa1`. Zusammen mit dem Passwort (Wissen)
  erreicht es `loa2`, wie SMS und Passwort in der Beispiel-Story. Die Obergrenze aus
  [ADR-5](adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md) regelt der Orchestrator:
  `enroll-totp` muss dafür nichts tun.

## 2) Das Modul anlegen

Je Verfahren ein Modul unter `K/tools/<modul>/`. Der Name des Moduls ist zugleich Datenbankschema,
Ordner der Migration und Datei `api/modules/<modul>.yaml`.

Alles, was das Modul über sich sagt, steht in einer Datei `<X>ToolModule.kt`: die Deklaration des
Verfahrens, darunter seine Tools als eigene Werte, und die Angaben für Spring Modulith. Für die
Einmalcode-App:

```kotlin
internal const val ENROLL_TOTP_TOOL_ID = "enroll-totp"
internal const val AUTH_TOTP_TOOL_ID = "auth-totp"
internal const val AUTH_TOTP_LOOKUP_TOOL_ID = "auth-totp-lookup"

internal val TotpModule = toolModule(
    method = "totp",
    name = Text("Einmalcode-App"),
    proves = factors(POSSESSION, upTo = AcrLevel.LOA1),
    stepData = TotpStepData,
)

internal val EnrollTotp = TotpModule.enroll(ENROLL_TOTP_TOOL_ID, versions = setOf(1), hint = Text("Authenticator-App einrichten"))
internal val AuthTotp = TotpModule.login(AUTH_TOTP_TOOL_ID, versions = setOf(1), hint = Text("Code aus der Authenticator-App"))
internal val AuthTotpLookup = TotpModule.lookupLogin(AUTH_TOTP_LOOKUP_TOOL_ID, versions = setOf(1), hint = Text("E-Mail-Adresse + Code aus der App"))

@ApplicationModule(id = "auth_totp", allowedDependencies = ["tool_api", "texts"])
@Configuration
internal class TotpToolModule {
    @Bean
    fun totpModule() = TotpModule
}
```

Eine Liste, in die man das Verfahren einträgt, gibt es nicht. Jedes `ToolModule`-Bean landet von
selbst im Katalog (`ToolHandlerRegistry`), in `GET /tools/catalog` und in den Kandidatenlisten der
Journeys, die nach Rolle auswählen. Name und Hinweis stehen nur hier: App und Login-Seite lesen sie
aus dem Katalog.

| Datei | Inhalt | Abschreiben von |
|---|---|---|
| `<X>ToolModule.kt` | Deklaration und Modulith-Angaben (siehe oben) | `tools/auth_kobil/KobilToolModule.kt`, `tools/ident_nect/NectToolModule.kt` |
| `internal/…ToolHandler.kt` | `start`, `patch`, `read`; gibt ein `ToolOutcome` zurück | `tools/auth_kobil/internal/enrollkobil/`, `…/authkobil/` |
| `internal/…Flow.kt` (optional) | die Entscheidung als reine Funktion, ohne Spring | `tools/auth_sms/internal/authsms/AuthSmsFlow.kt` |
| `api/v1/…ToolController.kt` | ein Controller je Tool (ADR-1) | `tools/auth_kobil/api/v1/`, `tools/ident_nect/api/v1/` |
| `api/v1/…StepData.kt` | die Formen von `stepData` als einfache Datenklassen, darüber ihre Deklaration (`kind`, Beschreibung, Beispiele), die das Modul unter `stepData` nennt | `tools/auth_kobil/api/v1/KobilStepData.kt` |
| `internal/…Enrollment.kt` + Repository | das gespeicherte Verfahren in `<modul>.enrollment`, mit der Konstante für den Enrollment-Typ | `tools/auth_kobil/internal/KobilEnrollment.kt` |
| `internal/…EnrollmentCleanup.kt` | löscht das Verfahren mit dem Konto | `tools/auth_sms/internal/AuthSmsEnrollmentCleanup.kt` |
| `internal/…/…ToolSession.kt` | die Arbeitsdaten eines Durchlaufs als einfache Datenklasse, gespeichert über `ToolSessionData` (keine Tabelle, kein Aufräumen) | `tools/auth_sms/internal/authsms/AuthSmsToolSession.kt` |

Ein Handler meldet in seinem Ergebnis nur, was von der Deklaration abweicht. `auth-totp` meldet bei
Erfolg einfach `ToolOutcome.Completed.Authenticated()`: `amr`, Niveau und Faktoren ergänzt der
Orchestrator aus dem Modul.

Die Controller sind dünn. Jeder implementiert `ToolController` und zeigt auf sein Tool
(`override val tool = AuthTotp`). Dann rufen sie `ToolJourney` und den Handler in fester Reihenfolge:

- `POST /tools/api/<toolId>/v1?channel={channelSessionId}`: Parameter `context: ActivationToolContext`, dann
  `handler.start`, `activated` (bucht das Ergebnis und antwortet `201` mit `Location`).
- `PATCH /tools/api/<toolId>/v1/{toolSessionId}`: Parameter `context: AuthorizedToolContext`, dann
  `handler.patch`, `applyOutcome`.
- `GET`: Parameter `context: ToolContext`, dann `readResponse { handler.read(…) }`.
- Hat eine Methode einen `@RequestBody`, steht er vor dem Kontext. Spring löst die Parameter der
  Reihe nach auf; so aktiviert eine unlesbare Anfrage nichts, und alle Endpunkte lesen gleich.
- `back` und `DELETE` sind allgemein (`LeaveToolController`); dafür schreibt man nichts.

Den Kontext löst der Orchestrator vor dem Aufruf auf, schon gegen den Schlüssel des Aufrufers
geprüft; eine Annotation braucht es nicht, der Typ sagt alles. Ein `ActivationToolContext`
aktiviert das Tool auf dem Kanal aus dem Pfad und legt dabei die Tool-Sitzung an; nur er wird von
`activated` angenommen. Ein `AuthorizedToolContext` lädt die Sitzung aus dem Pfad und verlangt, dass
sie der aktuelle Schritt ist; nur er darf die Journey ändern. Ein `ToolContext` lädt sie nur zum
Lesen. Welches Tool gemeint ist, kommt vom Controller. Einen Kontext (oder `@BindingKey`) muss jede
Methode haben, nur ein `ToolController` darf ihn nutzen, und der Body kommt vor dem Kontext; das
erzwingt `ApiBoundaryArchitectureTest`.

## 3) Bank-Ident: Weiterleitung und Fremdsystem

**Das Fremdsystem.** Die Bank ist ein eigenes Simulationsmodul `K/simulation/bank/`, wie
`simulation/nect`. Sein Dienst `BankIdent` ist der Port: Fall anlegen, Sprungadresse nennen, Ergebnis
einlösen. Dazu kommt ein Controller `/mock-bank` mit `@DemoSurface`, den es nur im Demomodus gibt
(erzwingt `ApiBoundaryArchitectureTest`). `ident-bank` deklariert deshalb `demoOnly`, solange keine
echte Bank angebunden ist. Was die echte Bank zusagen muss, gehört als eigener Abschnitt in
[port-vertraege.md](port-vertraege.md): Einlösen nur auf dem Server, nur einmal, nur für den Fall,
den dieses Tool angelegt hat.

**Die Weiterleitung** läuft wie bei Nect ([ADR-47](adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md)):

1. Das Anlegen nimmt eine `returnUri` entgegen. Sie muss mit einem erlaubten Präfix beginnen
   (`ident-bank.return-uri-prefixes`, als `@ConfigurationProperties` wie
   `tools/ident_nect/internal/IdentNectProperties.kt`).
2. `stepData` nennt die Sprungadresse zur Bank.
3. Die Bank leitet mit `?bankCaseId=…` zurück. Der `PATCH` mit dieser Kennung lässt den Orchestrator
   das Ergebnis selbst bei der Bank abholen. Dem Browser glaubt er nichts.

**Was die Bank liefert.** Familienname, Vornamen und Geburtsdatum bringt jede Identifizierung von
selbst mit (`identify(…)`); dazu die Anschrift, nie eine KVNR: Die darf nur aus dem
Personenverzeichnis kommen, das prüft schon das Bauen des Moduls. Welche Person das ist, entscheidet
wie bei `ident-nect` der Orchestrator über die Stammdaten (`IdentityResolver`), nicht das Tool.
Liefert die Bank zusätzlich eine eigene, dauerhafte Kennung der Person, deklariert das Modul dafür
einen eigenen Anker, wie `ident_nect` sein `NECT_RESTRICTED_ID`:

```kotlin
internal val BANK_CUSTOMER_ID = AttributeType.anchor(
    "bank_customer_id", AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2),
    allowsReplacement = true, retractableByHolder = false, caseSensitive = true, normalize = { it.trim() },
)
```

Der Kern ändert sich dafür nicht: Die Regeln des Ankers reisen mit dem Typ.

**Stolperstelle in der App.** Die Rückkehr von Nect ist in `AppChannelApp.tsx` fest verdrahtet
(`nectCaseId`). Ein zweites Verfahren mit Weiterleitung braucht dort seinen eigenen Parameter. Wer
das zum zweiten Mal anfasst, sollte es verallgemeinern. Im Web-Kanal ist nichts zu tun: Keycloak
reicht jeden fremden Parameter der Rückkehr als Eingabe an das Tool weiter.

## 4) Einmalcode-App: Einrichten und Anmelden

**Einrichten (`enroll-totp`).**

1. `start` erzeugt ein zufälliges Geheimnis und legt es in der Tool-Sitzung ab. `stepData` liefert
   die `otpauth://`-Adresse, die die App als QR-Code zeigt.
2. Die Person scannt den Code mit ihrer Authenticator-App und gibt den ersten Code ein.
3. Stimmt er, wird das Geheimnis zum Verfahren: eine Zeile in `auth_totp.enrollment`, und das Tool
   meldet `ToolOutcome.Completed.Enrolled` mit deren `EnrollmentRef`. Stimmt er nicht, meldet es
   `Failed.NothingGuessed`. Beim Einrichten gibt es nichts zu erraten.

**Anmelden (`auth-totp`).** Der Controller holt das eingerichtete Verfahren mit
`toolJourney.requireEnrollment(context, TotpModule)`; gibt es keins, antwortet der Orchestrator mit
`422`. Bei einem Verfahren mit `onePerDevice` wäre es von selbst das auf diesem Gerät. Der Handler prüft den Code gegen das Geheimnis und meldet
`Completed.Authenticated` oder `Failed.KnownAccountAuth`. Zählen und Sperren nach zu vielen
Fehlversuchen übernimmt der Orchestrator. Das Tool merkt sich nur den zuletzt angenommenen
Zeitschritt, damit derselbe Code nicht zweimal gilt.

**Ohne bekanntes Konto (`auth-totp-lookup`, optional).** Wie `auth-password-lookup`: E-Mail-Adresse
und Code. Wie beim Passwort steht die Vorbedingung dann am Einrichten: Ohne bestätigte Adresse wird
das Verfahren gar nicht erst angeboten
(`enroll(ENROLL_TOTP_TOOL_ID, requires = setOf(ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN)))`). Geht die Adresse
verloren, fällt das Verfahren mit ([ADR-24](adr/ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md)).

**Neu für dieses Projekt: ein Geheimnis, das lesbar bleiben muss.** Passwörter liegen als Hash, die
Geräteverfahren speichern nur öffentliche Schlüssel, SMS und E-Mail schicken Codes, die als Hash
gespeichert werden. TOTP ist das erste Verfahren, dessen Geheimnis der Server im Klartext braucht,
um jeden Code nachzurechnen. Eine Verschlüsselung gespeicherter Daten gibt es im Projekt noch nicht.
Vor einem echten Betrieb gehört das Geheimnis verschlüsselt abgelegt, und der Schlüssel dafür
gehört nicht in dieselbe Datenbank ([Idee Umschlagverschlüsselung](ideen/verschluesselung-differenzierte-aufbewahrung.md),
Zielbild der Schlüsselverwaltung `DPoP-demo-61kp`).

## 5) Was der Orchestrator übernimmt und was man doch anfasst

Von selbst, ohne Änderung im Kern:

- Kandidatenlisten, Ausweichwege und die Bewertung des Niveaus (`AuthPolicy`) richten sich nach Rolle,
  Faktoren und `maxAcr` aus dem Modul.
- Ein eigener Anker und mehrere Instanzen je Konto (`onePerDevice`) brauchen keine Änderung im Kern
  und keine Migration im Konto.
- Das Löschen des Kontos findet jedes `EnrollmentCleanup`. Die Arbeitsdaten der Tools liegen an der
  Tool-Sitzung des Orchestrators und gehen mit ihr (ADR-49).
- Neue Migrationsordner und neue `@RestController` werden gefunden; es gibt keine Listen.
- Der Kern darf ein neues Tool gar nicht beim Namen kennen: `CoreNamesNoToolTest` verbietet
  `ToolId("…")` im Kern.

Von Hand:

- **Reihenfolge und Schalter je Kanal** in `application.yml` (`demo.tool-defaults.channels`). Ohne
  Eintrag steht das Tool hinter allen eingeordneten.
- **Die Sprungseite der simulierten Bank**, falls sie eine eigene Oberfläche hat: `PAGE_APPS` in
  `WebConfig.kt` und ein Einstieg im Frontend wie `entries/nect`.

## 6) Datenbank, Vertrag, Frontend, Keycloak, Texte

- **Datenbank.** Je Modul ein Ordner `db/migration/<modul>/`, Versionen laufen über alle Module
  durch. Regeln in `db/migration/KONVENTIONEN.md`: eigenes Schema, `enrollment` für das dauerhafte
  Verfahren, keine Fremdschlüssel über Schemagrenzen. Für die Arbeitsdaten eines Durchlaufs braucht es
  keine Tabelle.
  Vorbild `auth_kobil/V12__auth_kobil.sql`.
- **Vertrag.** `./gradlew updateOpenApiSnapshot` schreibt `api/openapi.yaml`,
  `api/modules/<modul>.yaml` und je Tool `api/contract/tools/<toolId>/v1.yaml`, danach
  `./gradlew generateFrontendApiTypes`. Ein neues Tool ist kein Bruch;
  `checkPublishedApiCompatibility` meldet es als Hinweis, bis `publishApiVersion` es einfriert
  ([05-api.md](05-api.md) Abschnitt 1).
- **Frontend.** Je Modul `frontend/src/tools/<name>/index.tsx` mit einem Eintrag je Tool
  (`toolId`, `version` als die eine Fassung, die das Frontend spricht, `meta` mit dem Symbol,
  `explain`, `render`); Name und Hinweis kommen aus dem Katalog.
  Neue Tools gehören auch in `tools/catalog.fixture.json`, den Katalog der Unit-Tests
  (`ToolAvailabilityIntegrationTest` prüft ihn gegen den echten). Die Registry findet die Datei von
  selbst; einen Routing-Eintrag gibt es nicht. Vorbilder `tools/kobil/` und `tools/nect/`.
- **Keycloak.** Je Tool, das im Web-Kanal laufen soll, ein `WebToolRendererFactory` in der
  Erweiterung (mit `version()`, der Fassung, die der Renderer spricht), eingetragen in
  `META-INF/services`, mit Template für FreeMarker und Keycloakify
  ([ADR-41](adr/ADR-041-keycloakify-neben-freemarker.md)). Vorbilder `webtool/identnect/` und
  `webtool/sms/`. Was keinen Renderer hat, bietet der Web-Kanal nie an. Titel und Hinweis der Seite
  holt die Erweiterung aus dem Katalog (`OrchestratorToolCatalog`), der Renderer nennt sie nicht.
- **Texte.** Nutzertexte als deutsche Vorlage im Code (`Text("…")`, `t("…")`), danach
  `/translate-texts` ([ADR-33](adr/ADR-033-texte-als-vorlage-im-code.md)). Name und
  Hinweis eines Tools landen über die Moduldeklaration im Bündel `app`.

## 7) Was der Build erzwingt

Diese Tests werden rot, wenn etwas fehlt. Man liest sie am besten als Checkliste:

| Test | Meldet |
|---|---|
| `ModulithStructureTest` | Modul falsch angelegt oder eine nicht erlaubte Abhängigkeit |
| `ToolControllerMappingTest` | deklarierte Fassung ohne Controller oder Controller für eine nicht deklarierte Fassung, ein Pfad liegt nicht unter `/tools/api/<toolId>/v<N>` des eigenen Tools, oder das Modul fehlt in `StrategyTestFixtures.modules` (mit Hinweis, was zu tun ist) |
| Integrationstests (`IntegrationTestSupport.post`) | ein Tool beginnt bei der Aktivierung ohne Eingabe nicht mit seinem deklarierten `startStep` |
| `ApiBoundaryArchitectureTest` | Controller-Methode ohne Tool-Kontext oder `@BindingKey`, Kontext vor dem Body, Simulation ohne `@DemoSurface` |
| `SimulationBoundaryArchitectureTest` | Code außerhalb des Tools greift auf die Simulation zu |
| `OpenApiSnapshotTest`, `StepDataExamplesTest` | Vertrag nicht erneuert, Beispiel mit unbekannter Form |
| `TextTranslationsTest` (`-PstrictTexts`), `KcTextCatalogTest` | Texte nicht übersetzt, Name in App und Login-Seite verschieden |
| `frontend/src/tools/registry.test.ts` | doppelte `toolId`, fehlender Name oder fehlende Erklärung |

Vieles, was früher ein Test meldete, lässt der Aufbau gar nicht mehr zu: eine Identifizierung ohne
Name, Vornamen und Geburtsdatum, eine Rolle zweimal, verschiedene Niveaus innerhalb einer Methode.

Für die Integrationstests gibt es eine Stelle, die man von Hand nachträgt: Sie laufen gegen einen
festen Katalog (`StrategyTestFixtures.modules`), damit ihre erwarteten Kandidatenlisten sich nicht
ändern, wenn anderswo ein Modul dazukommt. Fehlt das neue Modul dort, sagt `ToolControllerMappingTest`
genau das. Es einzutragen, kann Erwartungen an Kandidatenlisten in den Strategietests ändern; das ist
dann eine echte Auswirkung des neuen Verfahrens auf das Angebot.

Die eigenen Tests schreibt man ab: `*ToolHandlerTest` und `*FlowTest` ohne Spring
(`tools/auth_kobil/internal/`), ein Integrationstest über HTTP (`IdentNectIntegrationTest`,
`KobilBindingIntegrationTest`) und ein Test der Simulation (`simulation/nect/NectIdentTest.kt`).

## 8) Doku nachziehen

Kein Test erzwingt die Doku, außer dass jeder Verweis auf Code auflösen muss (`DocReferencesTest`).
Mindestens:

- [03-tool-architektur.md](03-tool-architektur.md): Tabelle des Katalogs, Abschnitt „Was `ident-bank`
  von der Bank bekommt“.
- [06-ablaeufe.md](06-ablaeufe.md): ein Abschnitt je Verfahren.
- [port-vertraege.md](port-vertraege.md): der Vertrag mit der Bank.
- [08-projektrahmen.md](08-projektrahmen.md): die neuen Module in Liste und Diagramm.
- Glossar: `ident-bank`, `totp` und der Unterschied zu Keycloaks `otp`.
- Eine eigene ADR, wenn eine Entscheidung fällt, etwa „eigenes TOTP statt Keycloaks OTP“.

## 9) Eine neue Fassung eines Tools einführen

Später, wenn das Verfahren läuft und Clients ausgeliefert sind, ändert sich ein Tool so, dass alte
Clients es nicht mehr bedienen könnten. Ob das eine neue Fassung braucht, sagt die Tabelle in
[05-api.md](05-api.md) Abschnitt 1; die Entscheidungen dahinter stehen in
[ADR-51](adr/ADR-051-versionen-als-pfadsegment.md). Vorbild ist `enroll-sms@2` (Einwilligung als
Pflichtfeld, [06-ablaeufe.md](06-ablaeufe.md) Abschnitt 4). Für `enroll-totp` sähe das so aus:

1. **Entscheiden, was die alte Fassung ohne das Neue tut:** einen Ersatzwert setzen, weniger liefern
   oder abgeschaltet werden. Das ist eine fachliche Entscheidung, keine technische; sie kommt in den
   Abschnitt des Verfahrens in [06-ablaeufe.md](06-ablaeufe.md).
2. **Fassung deklarieren:** `versions = setOf(1, 2)` an der Deklaration im Modul, mit einem Satz,
   was Fassung 2 ausmacht.
3. **Ein Handler, Zweig nach Fassung.** Der Handler bekommt `ToolContext.version` vom Controller
   und verzweigt nur dort, wo sich das Verhalten unterscheidet. Jede solche Stelle ist mit
   „entfällt mit v1“ markiert, damit sie beim Ausbau alle gefunden werden. Arbeitsdaten, die nur
   Fassung 2 braucht, kommen mit Vorgabewert in die Tool-Sitzung.
4. **Ein Controller je Fassung,** im Paket `api.v2` des Moduls, mit eigenen DTOs und Pfaden unter
   `/tools/api/<toolId>/v2`. Der Controller von Fassung 1 bleibt unverändert, bis auf die Übergabe
   der Fassung an den Handler. Ändert sich eine `StepData`-Form, bekommt sie eine neue `kind`; ein
   neues Feld in `missingFields` braucht keine.
5. **Vertrag:** `./gradlew updateOpenApiSnapshot` schreibt `api/contract/tools/<toolId>/v2.yaml`;
   `v1.yaml` darf sich dabei inhaltlich nicht ändern. `checkPublishedApiCompatibility` meldet die
   neue Fassung als Hinweis, `publishApiVersion` friert sie ein. Danach `generateFrontendApiTypes`.
6. **Clients:** Jeder Client spricht genau eine Fassung. Im Frontend steht sie als `version` am
   Tool-Modul, in der Keycloak-Erweiterung als `version()` an der Renderer-Fabrik. Umgestellt wird
   der Client, der das Neue zeigen kann; die anderen bleiben bei ihrer Fassung.
7. **Tests:**
   - Unit-Tests für den Zweig im `*FlowTest` und `*ToolHandlerTest`.
   - Ein Integrationstest, der beide Fassungen nebeneinander durchspielt
     (`EnrollSmsVersionsIntegrationTest`): ein Kanal mit `@2` und einer mit `@1`, ein Aufruf in der
     anderen Fassung gibt `409`, das Audit nennt die Fassung.
   - `ToolControllerMappingTest` verlangt den Controller der neuen Fassung von selbst. Die
     Integrationstests deklarieren standardmäßig Fassung 1 und laufen unverändert weiter.
   - Die Katalog-Vorlage der Frontend-Tests (`tools/catalog.fixture.json`) nennt die neue Fassung.
8. **Doku:** den Abschnitt des Verfahrens in [06-ablaeufe.md](06-ablaeufe.md) um beide Fassungen
   ergänzen.

Ausgerollt wird erst der Server mit beiden Fassungen, dann der Client mit der neuen.
