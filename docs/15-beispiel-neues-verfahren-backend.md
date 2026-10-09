# Beispiel für Backend-Entwickler: Bank-Ident und Einmalcode-App anbinden

Dieses Kapitel ist das Gegenstück zur [Beispiel-Story](11-beispiel-story.md). Dort erlebt eine
Nutzerin das System. Hier baut ein Entwickler zwei neue Verfahren ein.

Das Kapitel ist für das **Backend** geschrieben. Es behandelt alles, was der Server und der
Web-Kanal (die Anmeldung über die Website mit Keycloak) brauchen:

- die Tools im Orchestrator, dem Server dieses Projekts,
- die Keycloak-Erweiterung,
- das Login-Theme, also die Gestaltung der Anmeldeseiten von Keycloak.

Was die App dafür tun muss, steht im Schwesterkapitel
[17-beispiel-neues-verfahren-app.md](17-beispiel-neues-verfahren-app.md). Beide Seiten verbindet
der Vertrag unter `api/`, also die verbindliche Beschreibung der Schnittstelle
([05-api.md](05-api.md)).

Beide Verfahren sind ausgedacht. Sie sind aber so gewählt, dass sie fast jede Stelle berühren, an
der ein neues Verfahren angebunden wird:

- **Bank-Ident** (`ident-bank`): Die Person identifiziert sich über ihre Hausbank. Die App leitet
  sie zur Bank weiter. Die Bank prüft die Person mit ihrer eigenen Kundenprüfung. Danach holt der
  Orchestrator das Ergebnis selbst bei der Bank ab. Vorbild ist `ident-nect`.
- **Einmalcode-App** (`enroll-totp`, `auth-totp`): Die Person richtet eine Authenticator-App ein
  und meldet sich danach mit dem sechsstelligen Code aus dieser App an. Das Verfahren dahinter heißt
  TOTP (zeitbasiertes Einmalpasswort nach RFC 6238). Vorbilder sind `auth_kobil` (Einrichten und
  Anmelden) und `auth_sms` (die kleinste Form eines Verfahrens).

Das Kapitel ist eine Übersicht, keine Bauanleitung Zeile für Zeile. Zu jedem Schritt nennt es die
Datei, die man als Vorlage nimmt, und den Test, der meldet, wenn etwas fehlt. Pfade, die mit `K/`
beginnen, stehen für `src/main/kotlin/com/example/identity/`.

---

## 1) Zuerst entscheiden: Rollen, Methoden, Niveaus

Bevor man Code schreibt, legt man fest, was das Verfahren fachlich ist. Dafür braucht man vier
Begriffe:

- Ein **Tool** ist ein abgeschlossener Arbeitsschritt, den der Nutzer durchläuft, etwa „SMS
  einrichten“ oder „mit Passwort anmelden“.
- Die **Rolle** eines Tools (`ToolRole`) sagt, was es fachlich tut: zum Beispiel identifizieren,
  ein Verfahren einrichten oder anmelden.
- Die **Methode** ist der Name des Anmeldeverfahrens im Code, etwa `sms` oder `totp`.
- Das **Niveau** sagt, wie sehr man einer Anmeldung vertraut, von `loa1` bis `loa3`. `maxAcr` ist
  das höchste Niveau, das ein Tool liefern kann.

Ein Verfahren ist ein `ToolModule` mit einem Tool je Rolle. Ein Verfahren, das identifiziert
**und** anmeldet, besteht deshalb aus mehreren Tools. Eine Identifizierung richtet nie ein
dauerhaftes Verfahren ein ([03-tool-architektur.md](03-tool-architektur.md) Abschnitt 2). In
diesem Beispiel sind es zwei Module, weil Identifizierung und Anmeldung verschiedene Methoden sind.

| Tool | Rolle | Methode | Faktoren | `maxAcr` | Modul |
|---|---|---|---|---|---|
| `ident-bank` | `IDENTIFICATION` | `bank` | Besitz + Wissen (die starke Kundenauthentifizierung der Bank) | `loa2` | `tools/ident_bank` |
| `enroll-totp` | `ENROLLMENT` | `totp` | Besitz | `loa1` | `tools/auth_totp` |
| `auth-totp` | `KNOWN_ACCOUNT_AUTH` | `totp` | Besitz | `loa1` | `tools/auth_totp` |
| `auth-totp-lookup` (optional) | `ACCOUNT_LOOKUP_AUTH` | `totp` | Besitz | `loa1` | `tools/auth_totp` |

Die Spalte „Faktoren“ nennt die Art des Beweises: etwas, das man besitzt (Besitz), oder etwas, das
man weiß (Wissen).

Was dabei zu bedenken ist:

- **Die IDs sind fest.** `enroll-totp`, `auth-totp` und `auth-totp-lookup` stehen je einmal als
  Konstante im Modul und werden für Deklaration und Controller verwendet. Sie müssen zur Methode
  `totp` und zur Rolle passen. Das prüft das Bauen des Moduls.
- **Faktoren und Niveau gelten für die ganze Methode.** Sie stehen einmal im Modul. Ein einzelnes
  Tool kann nichts anderes angeben.
- **Eigene `amr`-Werte.** `amr` ist die Liste der Verfahren, mit denen sich ein Nutzer in der
  Sitzung angemeldet hat; sie steht später im Token. `amr`-Werte und Methodennamen teilen sich einen
  Namensraum. `ident-bank` meldet deshalb etwa `bank-kyc` und nicht `bank`, so wie `ident-nect` den
  Wert `nect-eid` meldet.
- **Die Methode heißt `totp`, nicht `otp`.** Keycloak bringt ein eigenes OTP-Verfahren mit. Der
  Orchestrator kennt es als natives Verfahren (`kc-otp-form` mit der Methode `otp`, siehe
  `NativeAuthenticatorRegistry`). Das neue Tool ist ein anderes Verfahren mit einem anderen
  Geheimnis und braucht deshalb einen anderen Namen.
- **Warum überhaupt ein eigenes TOTP, wenn Keycloak eins hat?** [ADR-8](adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md)
  legt fest: Was Keycloak selbst kann, nutzt man. Die App spricht aber nicht mit Keycloak, und
  dieselbe Authenticator-App soll in beiden Kanälen gelten. Der Grund für das eigene Tool ist hier,
  dass das Geheimnis nur an einer Stelle liegen soll. Wer nur den Web-Kanal braucht, nimmt das OTP
  von Keycloak und baut nichts.
- **Niveaus.** TOTP allein beweist Besitz und erreicht `loa1`. Zusammen mit dem Passwort (Wissen)
  erreicht es `loa2`, so wie SMS und Passwort in der Beispiel-Story. Die Obergrenze aus
  [ADR-5](adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md) setzt der Orchestrator durch.
  `enroll-totp` muss dafür nichts tun.

## 2) Das Modul anlegen

Jedes Verfahren bekommt ein eigenes Modul unter `K/tools/<modul>/`. Der Name des Moduls ist
zugleich der Name des Datenbankschemas, des Ordners für die Migrationen und der Datei
`api/modules/<modul>.yaml`.

Alles, was das Modul über sich aussagt, steht in einer Datei `<X>ToolModule.kt`:

- die Deklaration des Verfahrens,
- darunter seine Tools als eigene Werte,
- die Angaben für Spring Modulith, das Framework, das die Grenzen zwischen den Modulen prüft.

Für die Einmalcode-App sieht das so aus:

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

Es gibt keine Liste, in die man das Verfahren eintragen muss. Jedes `ToolModule`-Bean wird
automatisch aufgenommen:

- in den Katalog (`ToolHandlerRegistry`),
- in die Antwort von `GET /tools/catalog`,
- in die Kandidatenlisten der Journeys, die nach Rolle auswählen. Eine Journey ist ein laufender
  Ablauf mit mehreren Schritten, etwa eine Registrierung.

Name und Hinweis des Verfahrens stehen nur hier. App und Login-Seite lesen sie aus dem Katalog.

Diese Dateien gehören zu einem Modul:

| Datei | Inhalt | Vorlage |
|---|---|---|
| `<X>ToolModule.kt` | Deklaration und Angaben für Spring Modulith (siehe oben) | `tools/auth_kobil/KobilToolModule.kt`, `tools/ident_nect/NectToolModule.kt` |
| `internal/…ToolHandler.kt` | die Methoden `start`, `patch` und `read`; jede gibt ein `ToolOutcome` zurück | `tools/auth_kobil/internal/enrollkobil/`, `…/authkobil/` |
| `internal/…Flow.kt` (optional) | die Entscheidung als reine Funktion, ohne Spring | `tools/auth_sms/internal/authsms/AuthSmsFlow.kt` |
| `api/v1/…ToolController.kt` | ein Controller je Tool (ADR-1) | `tools/auth_kobil/api/v1/`, `tools/ident_nect/api/v1/` |
| `api/v1/…StepData.kt` | die Formen von `stepData` (den Daten, die der Client für einen Schritt bekommt) als einfache Datenklassen. Darüber steht ihre Deklaration (`kind`, Beschreibung, Beispiele), die das Modul unter `stepData` angibt | `tools/auth_kobil/api/v1/KobilStepData.kt` |
| `internal/…Enrollment.kt` + Repository | das gespeicherte Verfahren in `<modul>.enrollment`, mit der Konstante für den Enrollment-Typ | `tools/auth_kobil/internal/KobilEnrollment.kt` |
| `internal/…EnrollmentCleanup.kt` | löscht das Verfahren, wenn das Konto gelöscht wird | `tools/auth_sms/internal/AuthSmsEnrollmentCleanup.kt` |
| `internal/…/…ToolSession.kt` | die Arbeitsdaten eines Durchlaufs als einfache Datenklasse, gespeichert über `ToolSessionData` (keine eigene Tabelle, kein Aufräumen nötig) | `tools/auth_sms/internal/authsms/AuthSmsToolSession.kt` |

Ein Handler meldet in seinem Ergebnis nur, was von der Deklaration abweicht. `auth-totp` meldet bei
Erfolg einfach `ToolOutcome.Completed.Authenticated()`. `amr`, Niveau und Faktoren ergänzt der
Orchestrator aus dem Modul.

### Die Controller

Die Controller enthalten kaum eigene Logik. Jeder implementiert `ToolController` und nennt sein Tool
(`override val tool = AuthTotp`). Dann ruft er `ToolJourney` und den Handler in fester Reihenfolge
auf:

- `POST /tools/api/<toolId>/v1?channel={channelSessionId}`: Parameter
  `context: ActivationToolContext`, dann `handler.start`, dann `activated`. `activated` übernimmt
  das Ergebnis und antwortet mit `201` und `Location`.
- `PATCH /tools/api/<toolId>/v1/{toolSessionId}`: Parameter `context: AuthorizedToolContext`, dann
  `handler.patch`, dann `applyOutcome`.
- `GET`: Parameter `context: ToolContext`, dann `readResponse { handler.read(…) }`.
- Hat eine Methode einen `@RequestBody`, steht er vor dem Kontext. Spring löst die Parameter der
  Reihe nach auf. So aktiviert eine Anfrage, die sich nicht lesen lässt, nichts, und alle Endpunkte
  lesen ihre Parameter gleich.
- `back` und `DELETE` sind für alle Tools gleich (`LeaveToolController`). Dafür schreibt man nichts.

**Der Kontext.** Den Kontext stellt der Orchestrator vor dem Aufruf bereit. Er hat ihn dann schon
gegen den Schlüssel des Aufrufers geprüft. Eine Annotation braucht es nicht, der Typ des Parameters
genügt. Es gibt drei Arten:

- Ein `ActivationToolContext` aktiviert das Tool auf dem Kanal aus dem Pfad und legt dabei die
  Tool-Sitzung an, also den einzelnen Durchlauf des Tools. Nur diesen Kontext nimmt `activated` an.
- Ein `AuthorizedToolContext` lädt die Tool-Sitzung aus dem Pfad und verlangt, dass sie der
  aktuelle Schritt ist. Nur mit diesem Kontext darf ein Tool die Journey ändern.
- Ein `ToolContext` lädt die Tool-Sitzung nur zum Lesen.

Welches Tool gemeint ist, kommt vom Controller. `ApiBoundaryArchitectureTest` erzwingt drei Regeln:
Jede Methode hat einen Kontext (oder `@BindingKey`). Nur ein `ToolController` darf ihn nutzen. Und
der Body steht vor dem Kontext.

## 3) Bank-Ident: Weiterleitung und Fremdsystem

**Das Fremdsystem.** Die Bank ist ein System außerhalb des Orchestrators. In der Demo wird sie
durch ein eigenes Simulationsmodul `K/simulation/bank/` ersetzt, so wie `simulation/nect`. Sein
Dienst `BankIdent` ist der **Port**, also die fest vereinbarte Schnittstelle zum Fremdsystem. Er
bietet drei Dinge: einen Fall anlegen, die Sprungadresse nennen und das Ergebnis einlösen.

Dazu kommt ein Controller `/mock-bank` mit `@DemoSurface`. Ihn gibt es nur im Demomodus; das
erzwingt `ApiBoundaryArchitectureTest`. `ident-bank` deklariert deshalb `demoOnly`, solange keine
echte Bank angebunden ist. Was die echte Bank zusagen muss, gehört als eigener Abschnitt in
[port-vertraege.md](port-vertraege.md): Das Ergebnis lässt sich nur auf dem Server einlösen, nur
einmal und nur für den Fall, den dieses Tool angelegt hat.

**Die Weiterleitung** läuft wie bei Nect ([ADR-47](adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md)):

1. Beim Anlegen nimmt das Tool eine `returnUri` entgegen, die Adresse für die Rückkehr. Sie muss mit
   einem erlaubten Präfix beginnen (`ident-bank.return-uri-prefixes`, als
   `@ConfigurationProperties` wie in `tools/ident_nect/internal/IdentNectProperties.kt`).
2. `stepData` nennt die Sprungadresse zur Bank.
3. Die Bank leitet mit `?bankCaseId=…` zurück. Mit dieser Kennung kommt ein `PATCH`, und der
   Orchestrator holt daraufhin das Ergebnis selbst bei der Bank ab. Angaben des Browsers übernimmt
   er dabei nicht.

**Was die Bank liefert.** Familienname, Vornamen und Geburtsdatum liefert jede Identifizierung
(`identify(…)`). Dazu kommt die Anschrift, aber nie eine KVNR (Krankenversichertennummer). Die
KVNR darf nur aus dem Personenverzeichnis kommen; das prüft schon das Bauen des Moduls. Welche
Person das ist, entscheidet wie bei `ident-nect` der Orchestrator anhand der Stammdaten
(`IdentityResolver`), nicht das Tool.

Liefert die Bank zusätzlich eine eigene, dauerhafte Kennung der Person, deklariert das Modul dafür
einen eigenen **Anker**. Ein Anker ist eine Angabe, über die sich ein Konto eindeutig wiederfinden
lässt. `ident_nect` hat zum Beispiel den Anker `NECT_RESTRICTED_ID`:

```kotlin
internal val BANK_CUSTOMER_ID = AttributeType.anchor(
    "bank_customer_id", AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2),
    allowsReplacement = true, retractableByHolder = false, caseSensitive = true, normalize = { it.trim() },
)
```

Der Kern des Orchestrators ändert sich dafür nicht. Die Regeln des Ankers sind vollständig im Typ
festgelegt.

**Rückkehr in den Kanal.** Im Web-Kanal ist nichts zu tun. Keycloak reicht jeden fremden Parameter
der Rückkehr als Eingabe an das Tool weiter, und die Erweiterung gibt die `returnUri` beim Anlegen
mit (Abschnitt 6). Die App braucht für die Rückkehr eigenen Code
([17-beispiel-neues-verfahren-app.md](17-beispiel-neues-verfahren-app.md) Abschnitt 3). Für das
Tool spielt es keine Rolle, aus welchem Kanal der `PATCH` kommt.

## 4) Einmalcode-App: Einrichten und Anmelden

**Einrichten (`enroll-totp`).**

1. `start` erzeugt ein zufälliges Geheimnis und legt es in der Tool-Sitzung ab. `stepData` liefert
   die `otpauth://`-Adresse und das Geheimnis zum Abtippen. Wie ein Kanal sie zeigt, entscheidet er
   selbst: im Web als QR-Code, in der App als Link in die Authenticator-App.
2. Die Person scannt den Code mit ihrer Authenticator-App und gibt den ersten Code ein.
3. Stimmt der Code, wird das Geheimnis zum eingerichteten Verfahren: Es entsteht eine Zeile in
   `auth_totp.enrollment`, und das Tool meldet `ToolOutcome.Completed.Enrolled` mit deren
   `EnrollmentRef`. Stimmt der Code nicht, meldet das Tool `Failed.NothingGuessed`. Beim Einrichten
   gibt es nämlich nichts zu erraten.

**Anmelden (`auth-totp`).** Der Controller holt das eingerichtete Verfahren mit
`toolJourney.requireEnrollment(context, TotpModule)`. Gibt es keins, antwortet der Orchestrator mit
`422`. Bei einem Verfahren mit `onePerDevice` wäre es automatisch das Verfahren auf diesem Gerät.
Der Handler prüft den Code gegen das Geheimnis und meldet `Completed.Authenticated` oder
`Failed.KnownAccountAuth`. Das Zählen der Fehlversuche und das Sperren nach zu vielen Fehlversuchen
übernimmt der Orchestrator. Das Tool merkt sich nur den zuletzt angenommenen Zeitschritt, damit
derselbe Code nicht zweimal gilt.

**Ohne bekanntes Konto (`auth-totp-lookup`, optional).** Dieses Tool funktioniert wie
`auth-password-lookup`: Die Person gibt E-Mail-Adresse und Code ein. Wie beim Passwort gilt dann
eine Vorbedingung schon beim Einrichten: Ohne bestätigte E-Mail-Adresse wird das Verfahren gar
nicht erst angeboten
(`enroll(ENROLL_TOTP_TOOL_ID, requires = setOf(ClaimRequirement(AttributeType.EMAIL, ClaimTrust.PROVEN)))`).
Geht die Adresse verloren, entfällt auch das Verfahren
([ADR-24](adr/ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md)).

**Neu für dieses Projekt: ein Geheimnis, das lesbar bleiben muss.** Bisher speichert kein
Verfahren ein Geheimnis im Klartext:

- Passwörter liegen als Hash vor.
- Die Geräteverfahren speichern nur öffentliche Schlüssel.
- SMS und E-Mail schicken Codes, die als Hash gespeichert werden.

TOTP ist das erste Verfahren, dessen Geheimnis der Server im Klartext braucht, um jeden Code
nachzurechnen. Verschlüsselt ist im Projekt bisher nur das Claim-Log
([ADR-52](adr/ADR-052-umschlagverschluesselung-des-claim-logs.md)). Vor einem echten Betrieb muss
das Geheimnis verschlüsselt abgelegt werden, etwa unter dem Hauptschlüssel des Kontos aus ADR-52,
und der Umschlagschlüssel dafür gehört nicht in dieselbe Datenbank (Schlüsseldienst,
[ADR-56](adr/ADR-056-vault-transit-als-schluesseldienst.md)).

## 5) Was der Orchestrator übernimmt und was man doch anfasst

Vieles erledigt der Orchestrator von selbst, ohne Änderung im Kern:

- Die Kandidatenlisten, die Ausweichwege und die Bewertung des Niveaus (`AuthPolicy`) richten sich
  nach Rolle, Faktoren und `maxAcr` aus dem Modul.
- Ein eigener Anker und mehrere Instanzen je Konto (`onePerDevice`) brauchen keine Änderung im Kern
  und keine Migration im Konto.
- Beim Löschen eines Kontos findet der Orchestrator jedes `EnrollmentCleanup`. Die Arbeitsdaten der
  Tools gehören zur Tool-Sitzung des Orchestrators und werden mit ihr gelöscht (ADR-49).
- Neue Migrationsordner und neue `@RestController` werden automatisch gefunden. Es gibt keine
  Listen.
- Der Kern darf ein neues Tool gar nicht beim Namen kennen. `CoreNamesNoToolTest` verbietet
  `ToolId("…")` im Kern.

Von Hand erledigt man:

- **Reihenfolge und Schalter je Kanal** in `application.yml` (`demo.tool-defaults.channels`). Ohne
  Eintrag steht das Tool hinter allen Tools, die eine feste Position haben.
- **Die Sprungseite der simulierten Bank**, falls sie eine eigene Oberfläche hat: einen Eintrag in
  `PAGE_APPS` in `WebConfig.kt` und einen eigenen Einstieg im Frontend wie `entries/nect`. Die
  Seite ist Teil der Simulation, nicht der App, und gehört deshalb zum Backend.

## 6) Web-Kanal: Keycloak-Erweiterung und Login-Theme

Im Web-Kanal zeigt Keycloak die Schritte eines Tools an. Ein Tool, für das die Erweiterung keinen
Renderer hat, bietet der Web-Kanal nie an. Ein Renderer ist die Klasse, die aus `stepData` eine
Anmeldeseite macht. Je Tool baut man drei Teile:

1. **Renderer** in `keycloak-extension/src/main/java/com/example/identity/kcext/webtool/<modul>/`:
   eine Klasse, die `AbstractWebToolRendererFactory` erweitert. Sie wird eingetragen in
   `META-INF/services/com.example.identity.kcext.webtool.WebToolRendererFactory`.
   - `getId()` liefert die toolId, `version()` die Fassung, die der Renderer beherrscht (ADR-51).
   - `template()` nennt die Seite (`tool-totp-enroll.ftl`). `render(...)` setzt die Werte der Seite
     aus `stepData`. Für einen Schritt, den die Seite nicht kennt, gibt es `null` zurück.
   - `activationFields` und `actionFields` braucht man nur, wenn das Tool beim Anlegen oder
     Absenden mehr braucht als die Formularfelder. `ident-bank` schickt dort wie `ident-nect` die
     Action-URL des laufenden Schritts als `returnUri`
     ([ADR-47](adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md)).
   - Titel und Hinweis der Seite kommen aus dem Katalog (`OrchestratorToolCatalog`). Der Renderer
     nennt sie nicht.
   - Vorbilder: `webtool/identnect/` (Weiterleitung), `webtool/sms/` (Einrichten und Anmelden).
2. **FreeMarker-Seite** unter `keycloak-extension/src/main/resources/theme/orchestrator/login/`,
   etwa `tool-totp-enroll.ftl`. FreeMarker ist die Vorlagensprache, mit der Keycloak seine Seiten
   erzeugt. Die Formularfelder heißen wie die Felder des `PATCH`. Keycloak schickt sie als Eingabe
   an das Tool. Für `enroll-totp` zeigt die Seite den QR-Code der `otpauth://`-Adresse und das
   Geheimnis zum Abtippen. Vorbild ist `tool-sms-enroll.ftl`.
3. **Keycloakify-Seite** im Theme `keycloak-theme/` ([ADR-41](adr/ADR-041-keycloakify-neben-freemarker.md)).
   Das ist dieselbe Seite noch einmal in React. Dafür braucht man:
   - einen Seitentyp mit den Werten des Renderers in `src/login/KcContext.ts`,
   - eine Komponente unter `src/login/pages/` (Vorbild `ToolSmsEnroll.tsx`),
   - einen Eintrag im `switch` von `src/login/KcPage.tsx`,
   - Beispielwerte in `src/login/mockContext.ts` für die Vorschau.

Die Texte der Seiten stehen als Vorlage im Code: `t.of("…")` in FreeMarker, `t("…")` im Theme. Sie
werden im Text-Bündel `keycloak` gesammelt.

Prüfen kann man das so:

- `./gradlew :keycloak-extension:test`,
- im Theme `npx tsc --noEmit` und `npm test`,
- das Zusammenspiel mit `npm run test:e2e:keycloak` im Frontend. Dafür muss der Compose-Stack
  laufen.

## 7) Datenbank, Vertrag, Texte

- **Datenbank.** Jedes Modul hat einen Ordner `db/migration/<modul>/`. Die Versionsnummern laufen
  über alle Module hinweg fortlaufend durch. Die Regeln stehen in `db/migration/KONVENTIONEN.md`:
  ein eigenes Schema, eine Tabelle `enrollment` für das dauerhafte Verfahren und keine
  Fremdschlüssel über Schemagrenzen hinweg. Für die Arbeitsdaten eines Durchlaufs braucht man keine
  Tabelle. Vorbild ist `auth_kobil/V12__auth_kobil.sql`.
- **Vertrag.** `./gradlew updateOpenApiSnapshot` schreibt drei Dateien: `api/openapi.yaml`,
  `api/modules/<modul>.yaml` und je Tool `api/contract/tools/<toolId>/v1.yaml`. Danach erzeugt
  `./gradlew generateFrontendApiTypes` die Typen für das Frontend.

  Ein neues Tool bricht den Vertrag nicht. `checkPublishedApiCompatibility` meldet es als Hinweis,
  bis `publishApiVersion` es als veröffentlicht festschreibt ([05-api.md](05-api.md) Abschnitt 4).

  Gleichzeitig aktualisiert man zwei Dinge: die erzeugten Typen unter `frontend/src/generated/` und
  die Katalog-Vorlage der Frontend-Tests (`frontend/src/tools/catalog.fixture.json`).
  `ToolAvailabilityIntegrationTest` prüft diese Vorlage gegen den echten Katalog. Der Build schlägt
  also schon an dieser Stelle fehl, nicht erst in der App. Danach hat die App alles, um mit ihrem
  Teil anzufangen.
- **Texte.** Nutzertexte stehen als deutsche Vorlage im Code (`Text("…")`, `t("…")`). Danach ruft
  man `/translate-texts` auf ([ADR-33](adr/ADR-033-texte-als-vorlage-im-code.md)). Name und Hinweis
  eines Tools kommen über die Moduldeklaration in das Text-Bündel `app`.

## 8) Was der Build erzwingt

Die folgenden Tests schlagen fehl, wenn etwas fehlt. Man liest sie am besten als Checkliste:

| Test | Meldet |
|---|---|
| `ModulithStructureTest` | Das Modul ist falsch angelegt, oder es gibt eine nicht erlaubte Abhängigkeit |
| `ToolControllerMappingTest` | Eine deklarierte Fassung hat keinen Controller, oder ein Controller gehört zu einer nicht deklarierten Fassung. Oder ein Pfad liegt nicht unter `/tools/api/<toolId>/v<N>` des eigenen Tools. Oder das Modul fehlt in `StrategyTestFixtures.modules` (mit Hinweis, was zu tun ist) |
| Integrationstests (`IntegrationTestSupport.post`) | Ein Tool beginnt bei der Aktivierung ohne Eingabe nicht mit seinem deklarierten `startStep` |
| `ApiBoundaryArchitectureTest` | Eine Controller-Methode hat keinen Tool-Kontext und kein `@BindingKey`, der Kontext steht vor dem Body, oder eine Simulation hat kein `@DemoSurface` |
| `SimulationBoundaryArchitectureTest` | Code außerhalb des Tools greift auf die Simulation zu |
| `OpenApiSnapshotTest`, `StepDataExamplesTest` | Der Vertrag ist nicht erneuert, oder ein Beispiel hat eine unbekannte Form |
| `TextTranslationsTest` (`-PstrictTexts`), `KcTextCatalogTest` | Texte sind nicht übersetzt, oder der Name ist in App und Login-Seite verschieden |
| `ToolAvailabilityIntegrationTest` | Die Katalog-Vorlage der Frontend-Tests weicht vom echten Katalog ab |

Vieles, was früher ein Test meldete, lässt der Aufbau des Codes gar nicht mehr zu. Dazu gehören:
eine Identifizierung ohne Name, Vornamen und Geburtsdatum, eine Rolle, die zweimal vorkommt, und
verschiedene Niveaus innerhalb einer Methode.

Für die Integrationstests gibt es eine Stelle, die man von Hand ergänzt. Diese Tests laufen gegen
einen festen Katalog (`StrategyTestFixtures.modules`). So ändern sich ihre erwarteten
Kandidatenlisten nicht, wenn anderswo ein Modul dazukommt. Fehlt das neue Modul dort, meldet
`ToolControllerMappingTest` genau das. Trägt man es ein, können sich Erwartungen an Kandidatenlisten
in den Strategietests ändern. Das ist dann eine echte Auswirkung des neuen Verfahrens auf das
Angebot.

Die eigenen Tests schreibt man nach diesen Vorlagen:

- `*ToolHandlerTest` und `*FlowTest` ohne Spring (`tools/auth_kobil/internal/`),
- ein Integrationstest über HTTP (`IdentNectIntegrationTest`, `KobilBindingIntegrationTest`),
- ein Test der Simulation (`simulation/nect/NectIdentTest.kt`).

## 9) Doku nachziehen

Kein Test erzwingt, dass die Doku aktuell ist. Nur eines wird geprüft: Jeder Verweis auf Code muss
auf etwas Vorhandenes zeigen (`DocReferencesTest`). Mindestens diese Stellen sollte man anpassen:

- [03-tool-architektur.md](03-tool-architektur.md): die Tabelle des Katalogs (Abschnitt 8).
- [verfahren/](verfahren/README.md): eine Seite je Verfahren, in der Übersicht verlinkt. Auf der
  Seite der Bank gehört ein Abschnitt „Was `ident-bank` von der Bank bekommt“ dazu.
- [port-vertraege.md](port-vertraege.md): der Vertrag mit der Bank.
- [08-projektrahmen.md](08-projektrahmen.md): die neuen Module in Liste und Diagramm.
- Glossar: `ident-bank`, `totp` und der Unterschied zu `otp` von Keycloak.
- Eine eigene ADR, wenn eine Entscheidung fällt, etwa „eigenes TOTP statt Keycloaks OTP“.

## 10) Eine neue Fassung eines Tools einführen

Später, wenn das Verfahren läuft und Clients ausgeliefert sind, kann sich ein Tool so ändern, dass
alte Clients es nicht mehr bedienen könnten. Ob das eine neue Fassung braucht, sagt die Tabelle in
[05-api.md](05-api.md) Abschnitt 2. Die Entscheidungen dahinter stehen in
[ADR-51](adr/ADR-051-versionen-als-pfadsegment.md). Vorbild ist `enroll-sms@2`, bei dem die
Einwilligung ein Pflichtfeld wurde ([Verfahren `sms`](verfahren/sms.md)). Für `enroll-totp` sähe
das so aus:

1. **Entscheiden, was die alte Fassung ohne das Neue tut.** Sie kann einen Ersatzwert setzen,
   weniger liefern oder abgeschaltet werden. Das ist eine fachliche Entscheidung, keine technische.
   Sie wird auf der Seite des Verfahrens unter [verfahren/](verfahren/README.md) festgehalten.
2. **Fassung deklarieren:** `versions = setOf(1, 2)` an der Deklaration im Modul, dazu ein Satz,
   was Fassung 2 ausmacht.
3. **Ein Handler, der nach Fassung verzweigt.** Der Handler bekommt `ToolContext.version` vom
   Controller. Er verzweigt nur dort, wo sich das Verhalten unterscheidet. Jede solche Stelle ist
   mit „entfällt mit v1“ markiert, damit man sie beim späteren Ausbau alle findet. Arbeitsdaten,
   die nur Fassung 2 braucht, kommen mit einem Vorgabewert in die Tool-Sitzung.
4. **Ein Controller je Fassung,** im Paket `api.v2` des Moduls, mit eigenen DTOs und Pfaden unter
   `/tools/api/<toolId>/v2`. Der Controller von Fassung 1 bleibt unverändert. Er gibt nur
   zusätzlich die Fassung an den Handler weiter. Ändert sich eine `StepData`-Form, bekommt sie eine
   neue `kind`. Ein neues Feld in `missingFields` braucht keine neue `kind`.
5. **Vertrag:** `./gradlew updateOpenApiSnapshot` schreibt `api/contract/tools/<toolId>/v2.yaml`.
   `v1.yaml` darf sich dabei inhaltlich nicht ändern. `checkPublishedApiCompatibility` meldet die
   neue Fassung als Hinweis, und `publishApiVersion` schreibt sie als veröffentlicht fest. Danach
   ruft man `generateFrontendApiTypes` auf.
6. **Web-Kanal:** Soll er die neue Fassung verwenden, stellt man `version()` am Renderer um und passt
   die FreeMarker- und die Keycloakify-Seite an (Abschnitt 6). Die App stellt ihre Fassung selbst
   um, sobald sie das Neue anzeigen kann
   ([17-beispiel-neues-verfahren-app.md](17-beispiel-neues-verfahren-app.md) Abschnitt 6). Bis
   dahin bleibt sie bei der alten Fassung.
7. **Tests:**
   - Unit-Tests für den neuen Zweig im `*FlowTest` und `*ToolHandlerTest`.
   - Ein Integrationstest, der beide Fassungen nebeneinander durchspielt
     (`EnrollSmsVersionsIntegrationTest`): ein Kanal mit `@2` und einer mit `@1`. Ein Aufruf in der
     jeweils anderen Fassung ergibt `409`, und das Audit nennt die Fassung.
   - `ToolControllerMappingTest` verlangt den Controller der neuen Fassung von selbst. Die
     Integrationstests deklarieren standardmäßig Fassung 1 und laufen unverändert weiter.
   - Die Katalog-Vorlage der Frontend-Tests (`frontend/src/tools/catalog.fixture.json`) nennt die
     neue Fassung.
8. **Doku:** Die Seite des Verfahrens unter [verfahren/](verfahren/README.md) beschreibt danach
   beide Fassungen.

Ausgeliefert wird zuerst der Server mit beiden Fassungen, danach der Client mit der neuen Fassung.
Die Keycloak-Erweiterung wird mit dem Server ausgeliefert und kann gleichzeitig umstellen.
