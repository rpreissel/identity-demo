# Dritte Bewertung (2026-09-27): Architektur, Konzept, Codequalität, Sicherheit

Umfang: der Backend-Kern (`core/`, `contract/`, `tools/`) und die Keycloak-Erweiterung samt
Realm-Migrationen. Simulationen und Demo-Anteile nur dort, wo sie mit dem Kern zusammenarbeiten
(Ports, `@DemoSurface`, `demo.mode`). Maßstab ist [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md):
Der Kern soll produktionsreif sein, jede Sicherheitszusage gilt ohne unbenannte Annahme an die
Umgebung.

Grundlage: vier getrennte Prüfungen (Sicherheit Kern, Keycloak-Erweiterung und -Anbindung,
Architektur und Konzept, Codequalität und Tests) mit vollständigem Lesen der zentralen Klassen,
dazu eigene Nachprüfung jedes Befunds, der hier als „mittel“ steht. Die zweite Bewertung vom
2026-09-26 ist abgearbeitet und gelöscht; ihre hohen Befunde wurden hier auf Regression geprüft
(Abschnitt 2).

## 1. Urteil in drei Sätzen

- **Der Kern hält, was die zweite Bewertung verlangt hat.** Kein Befund dieser Runde ist „hoch“.
  Die Sicherheitszusagen, die ADR-35 für den Kern fordert, sind heute per Typ, Constraint oder
  Test erzwungen: DPoP-Replay als Primärschlüssel-INSERT, Kanalbindung als Default-Deny, Konto-
  und Identitätswechsel nur im `JourneyActionExecutor`, signierte Anfragen und Antworten auf dem
  Hop zu Keycloak, Grant nur für den Orchestrator-Client.
- **Was bleibt, sind Zweitlinien-Lücken.** Ein Angreifer kann mit einer Anfrage alle 15 Minuten
  ein Konto oder den Admin dauerhaft sperren (S-2); der private Peer-Auth-Schlüssel ist über die
  Keycloak-Admin-API lesbar (K-1); die Erweiterung wertet eine Antwort ohne `next` als Anmeldung
  (K-2); ein hängender Keycloak hält Zeilensperren ohne Timeout (K-4). Nichts davon ist ohne eine
  zweite Schwäche ausnutzbar, aber jedes ist eine Annahme, die ADR-35 nicht benennt.
- **Die Architektur ist so stark wie ihre Prüfregeln, und drei davon haben Löcher.** Die
  Domain-Regel erlaubt jede `account`-Klasse statt der einen Lesesicht (A-5), die
  Transaktionsregel sieht methodenweise `@Transactional` und vier der fünf Keycloak-Aufrufer
  nicht (K-5), und I-19 ist nur gegen `println` gesichert, nicht gegen den Logger (S-1/A-6).
  Wo die Regel fehlt, ist die Konvention prompt gebrochen: `DefaultAuthPolicy` wählt den
  Descriptor nur nach `method` (A-4), drei Auth-Handler behandeln denselben Fall auf drei Arten
  (Q-1).

## 2. Regressionsprüfung der zweiten Bewertung

Alle hohen Befunde vom 2026-09-26 sind im Code geblieben, keiner ist zurückgefallen:

- Grant nur für den Orchestrator-Client: `AccountTokenGrantClients.isAllowed` vor allem anderen in
  `AccountTokenGrantType.process`; Migration V4 setzt das Attribut; Test vorhanden.
- Kein Schlüsselpaar je Konto: der Grant liest nur `account_id`, `acr`, `amr`, `session_id`.
- `ProductionModeCheck` prüft Admin-Passwort, H2-Konsole, Pepper und Lookup-Secret (≥ 32),
  `trustSelfSigned` und `http://`-URLs.
- `PhoneNumber`-Wertobjekt in `EnrollSmsFlow` und `SmsSendBudget`; QR-Bestätigungscode als HMAC mit
  Pepper; `htuMatches` mit exaktem Pfad; DPoP-Replay per nativem `INSERT` in eigener Transaktion;
  Argon2id mit Dummy-Hash für konstante Kosten.
- Peer-Auth nur `typ=peer-auth+jwt`, nur ES256, `kid` Pflicht.

## 3. Befunde

Reihenfolge innerhalb jedes Abschnitts nach Schwere. Jeder Punkt nennt Fundstelle, Szenario und
Gegenmaßnahme. „Bewusst“ heißt: eine ADR oder ein Kommentar legt das so fest; der Punkt steht
hier als benanntes Restrisiko, nicht als Fehler.

### 3.1 Sicherheit im Kern

- **S-1 (mittel) Nutzereingaben landen über den 400-Handler im Log; I-19 ist nur gegen `println`
  gesichert.** `OrchestratorExceptionHandler.handleIllegalArgument` loggt `e.message` jeder
  `IllegalArgumentException`, die keine `InvalidInputException` ist. `Email.of`, `Kvnr`,
  `Partnernr`, `InsuranceNumber` und `PhoneNumber` tragen den Rohwert in ihre Meldung
  (`"Invalid email: '$raw'"`). Die Lookup-Tools (`AuthSmsLookupToolController`,
  `AuthPasswordLookupToolController`, `AuthEmailLookupToolHandler`) reichen die Eingabe
  unvalidiert bis `AttributeRules` durch. Ergebnis: Adressen und Nummern mit Tippfehler stehen
  im Container-Log. Die ArchUnit-Regel zu I-19 prüft nur `System.out` und `ConsoleKt`; SLF4J
  sieht sie nicht, obwohl Spring Boot auf STDOUT loggt. Fix: Wertobjekte formulieren ihre
  Meldung ohne Rohwert; die Lookup-Controller prüfen `ofOrNull` vorher und werfen
  `InvalidInputException`; der Handler loggt bei fremden `IllegalArgumentException` nur den Typ.
  Mechanismus für I-19: ein Integrationstest mit Logback-`ListAppender`, der einen SMS- und
  E-Mail-Durchlauf fährt und prüft, dass weder TAN noch Empfänger in einem Log-Ereignis stehen;
  Register-Eintrag ergänzen.
- **S-2 (mittel) Sperrzähler verfällt nie: dauerhafte Sperre mit einer Anfrage je 15 Minuten.**
  `AttemptThrottleRepository.incrementFailure` erhöht `failed_count` immer und setzt
  `locked_until`, sobald der Zähler ≥ 5 ist; zurückgesetzt wird nur bei Erfolg. Nach der ersten
  Sperre löst jeder weitere Fehlversuch nach Ablauf sofort die nächste aus. Wer eine
  E-Mail-Adresse kennt, hält das Konto über `auth-*-lookup` dauerhaft gesperrt; der Inhaber
  kann den Zähler nicht zurücksetzen, weil er sich nicht anmelden kann. Dasselbe gilt für
  Personen (`ident-fsc`) und für den Admin-Benutzernamen (`AdminLoginThrottleFilter`): fünf
  falsche Basic-Auth-Versuche sperren den Operator, und ein Skript hält das am Laufen. Die
  Retention räumt aktive Sperren bewusst nie ab. Fix: im selben Statement den Zähler auf 1
  setzen, wenn `locked_until` abgelaufen ist (Vorbild `incrementWithinWindow`); für ADMIN eine
  IP-Komponente im Subject; Test in `AccountThrottleIntegrationTest` „nach Ablauf zählt der
  nächste Fehlversuch von vorn“; Verhalten in 07-betrieb §4 beschreiben.
- **S-3 (niedrig) Replay-Tabelle wächst unauthentifiziert.** `DpopBindingKeyResolver` schreibt
  je syntaktisch gültigem Proof eine Zeile in `dpop_proof_replay`, bevor Kanal oder Drossel
  geprüft werden; Schlüssel sind kostenlos. Kein Bypass, aber IO-Last auf der H2-Datei. Fix:
  IP-Drossel an der Edge; für den Betrieb der in 09-dpop angekündigte Key-Value-Speicher.
- **S-4 (niedrig) Hinter einem TLS-Proxy fällt jeder Proof durch, und der Fix macht HSTS
  manipulierbar.** `RequestUrls.buildRequestUrl` nimmt `scheme/serverName/serverPort` der
  Anfrage; hinter der OpenShift-Route ergibt das `http://…:8080`, der Client signiert
  `https://…`. `server.forward-headers-strategy` ist nicht gesetzt. Mit `framework` vertraut
  Spring `X-Forwarded-*` von jedem Absender. Fix: `native` (Tomcat `RemoteIpValve` mit
  `internal-proxies`) in der OpenShift-Konfiguration; HSTS explizit; CSP für `/app`, `/web`,
  `/admin` (siehe auch `DPoP-demo-dm2j`, `DPoP-demo-ai4x`).
- **S-5 (niedrig) 401-Antwort trägt den internen Fehlertext.** `handlePeerAuth` gibt „Unknown
  peer-auth key id: <kid>“ und „Unexpected peer-auth issuer: <iss>“ an jeden Aufrufer. Fix:
  neutraler Text für `PeerAuthValidationException`, Detail nur ins Log; für DPoP feste
  Fehlercodes statt freier Strings.
- **S-6 (niedrig, bewusst) Klartext-Geheimnisse in der Datenbank.** ADR-22 benennt PIN und
  Node-Schlüssel; die Refresh-Tokens in `auth_context` fallen in dieselbe Klasse, stehen aber
  nicht in der ADR. Fix: ADR-22 ergänzen; Verschlüsselung at rest ist als `DPoP-demo-bo1w` und
  `DPoP-demo-61kp` offen.
- **S-7 (Hinweis, bewusst) `demo.mode` ist per Default `true`.** Eine mit Voreinstellungen
  ausgerollte Instanz ist von jedem Besucher zurücksetzbar (`POST /orchestrator/demo/reset`).
  Alles hängt korrekt an `@DemoSurface`; die Doku sollte an einer Stelle sagen, dass
  `DEMO_MODE=false` die einzige Sicherung ist.
- **S-8 (Hinweis, bewusst) H2-Konsole.** Projektentscheidung, unverändert. Optional
  `web-allow-others: false` explizit pinnen, damit ein Umgebungs-Override auffällt.
- **S-9 (Hinweis) Keycloak-Bootstrap-Admin nicht vom Startcheck erfasst.** `ProductionModeCheck`
  prüft das Orchestrator-Admin-Passwort, nicht `KEYCLOAK_ADMIN_PASSWORD` (compose-Default
  `admin`). OpenShift erzeugt ein Zufallspasswort, compose nicht. Fix: in 07-betrieb als
  Pflicht benennen; optional prüft der Orchestrator beim Start, ob `admin/admin` im Master-Realm
  noch geht, und weigert sich außerhalb des Demomodus.
- **S-10 (niedrig, bewusst) Kein DPoP-Nonce.** Wie in 09-dpop §2 beschrieben; Proofs sind
  150 s im Voraus berechenbar. Bis zur Nonce `max-age-seconds` auf 60 senken.
- **S-11 (Hinweis, bewusst) Selbstsigniertes Keycloak-Zertifikat ohne Hostname-Prüfung** in den
  Varianten `host`/`compose`; nie JVM-weit (I-17), außerhalb des Demomodus verboten.
- **S-12 (Hinweis) Laufzeit-Image `ubi9/openjdk-21-runtime:latest`** in `compose.yml`. Alle
  Bibliotheksversionen sind aktuell. Fix: Digest oder feste Minor-Version pinnen.
- **S-13 (Hinweis) Swagger-UI und `/v3/api-docs` in jeder Umgebung erreichbar.** Read-only und
  im Repo ohnehin öffentlich; außerhalb des Demomodus abschalten oder in `ProductionModeCheck`
  aufnehmen.

### 3.2 Keycloak-Erweiterung und -Anbindung

- **K-1 (mittel) Der private Peer-Auth-Schlüssel liegt als undeklariertes Config-Property im
  Realm.** `OrchestratorSettings.ensureSigningKey` schreibt das komplette EC-Paar samt `d` als
  `peerAuthSigningKeyJwk` in `ComponentModel.config`. Das Property steht nicht in
  `CONFIG_PROPERTIES` und ist nicht als Secret markiert. Keycloaks `StripSecretsUtils` maskiert
  nur deklarierte Secret-Properties (nach Keycloak-Quelle; im laufenden System nicht geprüft).
  Damit erscheint der private Schlüssel im Klartext in `GET /admin/realms/{realm}/components/{id}`
  und im Realm-Export; wer `view-realm` hat, kann sich als Keycloak gegenüber dem Orchestrator
  ausweisen und zum Beispiel `MgmtPasswordController.set` für jedes Konto aufrufen. ADR-25 nimmt
  nur „Klartext in der Datenbank“ in Kauf. Fix: als `ProviderConfigProperty` vom Typ `PASSWORD`
  mit `setSecret(true)` deklarieren, oder den Schlüssel als Realm-Key (`KeyProvider`, dort ist
  `privateKey` Secret) ablegen; Test in der Erweiterung, dass die Repräsentation der Komponente
  den Schlüssel maskiert.
- **K-2 (mittel) Der Authenticator ist bei fehlendem `next` fail-open und prüft Konto und
  Niveau nicht selbst.** `OrchestratorAuthenticator.handleResponse`: `if (next == null ||
  next.isAuthenticated() || "AUTHENTICATED".equals(channelState)) context.success()`. Im
  API-Vertrag ist `next` optional, und `ManageMethods` nutzt `next == null` bereits als
  „Unter-Journey fertig“. Zusätzlich vergleicht die Erweiterung bei gesetztem `context.getUser()`
  (Step-up) `authDataAccountId` nicht mit dem Nutzer und `AUTHENTICATED` nicht mit `targetAcr`.
  Das Backend fängt Kontowechsel ab (`KcChannelService`) und hält den ACR-Floor; die
  Erweiterung verlässt sich vollständig darauf, obwohl sie beides selbst weiß. Fix: `next ==
  null` im Login-Flow ist `INTERNAL_ERROR`; Erfolg nur bei `next.isAuthenticated()`; bei
  gesetztem Nutzer `authDataAccountId` mit `OrchestratorNotes.accountId(user)` vergleichen;
  `authDataAcr` gegen `targetAcr` prüfen (`LOA_TO_ACR` kennt die Ordnung). Unit-Test für die
  drei Grenzen (`next=null`, Konto-Mismatch, Niveau zu niedrig).
- **K-3 (mittel, bewusst) Hop Keycloak → Orchestrator über Klartext-HTTP; Textbundle ungesichert.**
  Anfragen sind signiert, Antworten verifiziert; Mitlesen (Passwörter in `verifyPassword`,
  Adressen) verhindert das nicht, außerhalb des Demomodus erzwingt `ProductionModeCheck` https.
  Nicht von ADR-7 abgedeckt: `OrchestratorTexts.bundle` holt `/texts/{lang}` mit eigenem
  `HttpClient` ohne Assertion und ohne Signaturprüfung; wer auf dem Hop antwortet, bestimmt die
  Texte der Login-Seite (kein XSS, FreeMarker escaped; aber Phishing-Wortlaut). Fix: Texte über
  `OrchestratorClient.send` holen.
- **K-4 (mittel) Keine Timeouts Richtung Keycloak; der Aufruf läuft in der Journey-Transaktion.**
  `KeycloakHttp` nutzt `SimpleClientHttpRequestFactory()` ohne Connect- und Read-Timeout;
  `KeycloakJwkSource` lädt per `JWKSet.load(URL)` ohne Timeout. `KcTokenProvider.tokenFor` läuft
  laut ADR-43 bewusst in der Transaktion von `JourneyService.finish`. Ein Keycloak, der
  Verbindungen annimmt, aber nicht antwortet, hält damit die Zeilensperren unbegrenzt. Auf der
  Erweiterungsseite fehlt der Connect-Timeout (`HttpClient.newHttpClient()`). Fix: 3 s / 10 s
  in `KeycloakHttp`, `JWKSet.load(url, connect, read, sizeLimit)`, `HttpClient.newBuilder()
  .connectTimeout(...)` in der Erweiterung; Test, dass ein hängender Stub nach der Frist einen
  Fehler liefert.
- **K-5 (niedrig) Die Regel „keine Transaktion um Keycloak-Aufrufe“ ist lückenhaft.** Sie prüft
  nur klassenweit annotierte `@Transactional` (nicht die 23 methodenweisen) und nur die
  Abhängigkeit auf `KeycloakAdminClient`. `KeycloakRealmSessions`, `KeycloakLoa1Login`,
  `KeycloakRealmLoginTheme`, `KeycloakAdminUserSessions`, `KeycloakMigrationToken` sind ebenfalls
  Netzaufrufe und ungeschützt. Heute verletzt sie niemand (geprüft). Fix: Marker-Interface
  `KeycloakRoundTrip` für alle Netzklassen im `kc`-Paket; Regel auf
  `haveMethodsAnnotatedWith(Transactional)` erweitern und gegen das Interface prüfen.
- **K-6 (niedrig) Orchestrator-Ausfall zählt als falsches Passwort.**
  `OrchestratorStorageProvider.isValid` liefert bei `IOException` `false`; Keycloaks
  Brute-Force-Schutz zählt den Ausfall dem Nutzer an, ein kurzer Ausfall sperrt alle gerade
  Anmeldenden. `read` wirft dagegen `ModelException` (ADR-38: „nicht erreichbar ist ein Fehler,
  kein Nutzer unbekannt“). Fix: `isValid` wirft wie `read`.
- **K-7 (niedrig) `loa-max-age` 10 h, Token-Lebensdauern nur Defaults.** Ein einmal erreichtes
  `loa2` gilt in der SSO-Sitzung 36 000 s; das loa2-Gate vor `orchestrator-manage-methods`
  fordert danach bis zu 10 h keinen frischen Nachweis. `accessTokenLifespan`, SSO-Idle/Max und
  `sslRequired` sind in V1 nicht gesetzt und folgen Keycloak-Defaults. Fix: `loa-max-age` für
  LoA 2 auf wenige Minuten; die Lebensdauern und `sslRequired=external` explizit in V1, damit
  der Realm die Annahmen aus ADR-9/43 selbst trägt.
- **K-8 (niedrig) QR-Status-Endpunkt als Verstärker.** `GET /realms/{r}/orchestrator-qr/status`
  braucht nur einen Auth-Session-Cookie im Warteschritt und löst je Aufruf einen signierten
  `readTool` plus Replay-Zeile aus. Fix: serverseitiges Mindestintervall je Auth-Session
  (Note mit letztem Zeitpunkt, davor letztes Ergebnis oder 429).
- **K-9 (niedrig) Rohes JSON in `<script>` ohne Script-Escaping.**
  `AbstractWebToolRendererFactory` und `demo-person-picker.ftl` (`${personsJson?no_esc}`)
  betten `demo.persons` als JS-Literal ein; Jackson escaped `</script>` nicht. Heute nur
  Seed-Daten im Demomodus, kein erreichbarer Weg. Fix: `<`, ``, `` escapen oder
  per `data-`-Attribut und `JSON.parse` übergeben.
- **K-10 (niedrig, bewusst) compose-Voreinstellungen** (`admin/admin`, Keystore-Passwort
  `password`, `start-dev`, `KC_HOSTNAME_STRICT=false`). ADR-35 Phase G; offen als
  `DPoP-demo-9msv`, `DPoP-demo-x25a`. Siehe S-9.
- **K-11 (niedrig) Neuer `HttpClient` je Keycloak-Anfrage.** `OrchestratorSettings.newClient()`
  in jeder Factory-`create(session)`; eigener Executor und Pool je Anfrage, kein Keep-Alive.
  Fix: ein statischer Client mit Connect-Timeout (K-4), Instanz nur mit URL und Schlüssel.
- **K-12 (niedrig) Duplizierte Dispatch-Logik, Testlücken.** `OrchestratorAuthenticator` und
  `OrchestratorManageMethodsRequiredAction` teilen rund 80 Zeilen Select/Tool/Confirm/Notes.
  Ohne Test: `AccountTokenGrantType.process`/`continuedSession`, beide Dispatcher (K-2 hätte ein
  Test gefunden), `OrchestratorNotes.channelSessionIdFor`, der Fehlerpfad von `isValid` (K-6),
  `MigrationClientBootstrapFactory.ensureClient`. Fix: `NextDispatcher` mit Callback-Interface
  (challenge/success/failure); Unit-Tests mit gemockten `UserSessionModel`.
- **K-13 (Hinweis) Schlüsselrotation ohne Überlappung.** Rotation heißt „Config-Wert löschen“;
  das JWKS führt nur den aktuellen Schlüssel, laufende Anfragen scheitern für Sekunden.
  Spiegelbildlich `node_signing_key`. Fix: Rotationsablauf in 07-betrieb; optional aktiver und
  vorheriger Schlüssel im JWKS. Gehört zu `DPoP-demo-61kp`.
- **K-14 (Hinweis, bewusst) Peer-Auth-Zeitfenster 300 s im `keycloak`-Profil** (Default 30 s),
  begründet mit Uhrendrift der Podman-VM, gilt aber für jedes Deployment mit dem Profil. Fix:
  in die Variante `host` legen oder Obergrenze in `ProductionModeCheck`.
- **K-15 (Hinweis, bewusst) Grant übernimmt `acr`/`amr` ungeprüft; Fehlertext echot
  `session_id`.** ADR-9: der Orchestrator ist alleinige Autorität. Trotzdem kennt die
  Erweiterung die zulässigen Werte (`LOA_TO_ACR`). Fix: Whitelist `acr ∈ {loa1, loa2}`, `amr`
  gegen `[a-z0-9_-]+`; Session-Id nicht in die OAuth-Fehlerantwort.

### 3.3 Architektur und Konzept

- **A-1 (mittel) `DefaultAuthPolicy.reachability` wählt den Descriptor nur nach `method`.**
  `descriptorFor(method) = descriptors().firstOrNull { it.method == method }`. Für `qr` gibt es
  vier Descriptors mit verschiedenen `factorTypes` und `maxAcr` (`enroll-qr`: keine Faktoren,
  loa1; `auth-qr`: Besitz und Wissen, loa2). `reachability` ist die Grundlage für den
  Selbstaussperr-Schutz beim Entfernen einer Methode und für `afterEnrollment`; sie rechnet mit
  dem zufällig ersten. Heute rettet die alphabetische Klassenreihenfolge, garantiert ist das
  nicht. 03 §4 nennt `(method, role)` als Schlüssel, `CredentialRules` begründet selbst, warum
  nur `method` falsch ist. Fix: `ToolCatalog.descriptorOf(method, role)`; `reachability` fragt
  nach `IDENTIFIED_AUTH`; Test in `DefaultAuthPolicyTest`, der `descriptors()` in umgekehrter
  Reihenfolge liefert und gleiche `Reachability` verlangt.
- **A-2 (mittel) Die Domain-Regel erlaubt jede `account`-Klasse.** `OrchestratorArchitectureTest`
  verbietet `orchestrator.domain` nur Framework-Pakete und den Rest des Orchestrators; 08 §3
  erlaubt aus `account` genau die Lesesicht `AccountProfile`. Tatsächlich dürfte `domain` heute
  `AccountService` und JPA-Entitäten importieren, solange es selbst keine Spring-Annotation
  trägt; die Strategie-Regel fängt das nur für Strategien, nicht für `AccountRules`,
  `DefaultAuthPolicy`, `CandidateTools`. `io.swagger..` fehlt in der Verbotsliste. Fix:
  Positivliste (`AccountProfile`, `AuthMethodView`) statt Negativliste; `io.swagger..` und
  `java.util.logging..` verbieten; spiegelbildlich für `account.domain`.
- **A-3 (mittel) `MgmtPasswordController` baut `enroll-password` im Orchestrator nach.** Der
  Controller unter `core/orchestrator/api/v1/kc` hält `ToolId("enroll-password")` und
  `"password"` als Literale, schreibt den `PASSWORD_EXISTS`-Claim und die Methode selbst
  (`enrolledUnderAcr = null`, `source = kc-native`) und umgeht damit `ToolDescriptor`,
  `ToolOutcome.Enrolled`, den ADR-5-Deckel und den Protokollpfad des Executors. 08 sagt: „kein
  Methodenmodul verweist auf den Orchestrator und umgekehrt“. Die Kategorie „mgmt-Tool ohne
  ToolSession“ ist nirgends deklariert. Fix: Controller nach `auth_password.api.v1`; Peer-Auth
  als `tool_api`-Port; das Schreiben als `ToolOutcome.Completed.Enrolled` über einen
  `ToolJourney.applyStandaloneOutcome(accountId, descriptor, outcome)`, damit Claim, Methode und
  Deckel zentral bleiben; ArchUnit: keine `ToolId("…")`-Literale in `core.orchestrator`.
- **A-4 (mittel) `ConfirmPeerLoginState.Confirming` friert `ToolId("confirm-qr-login")` ein.**
  04 §Kandidaten sagt „dort steht keine einzige toolId“; `CandidateTools` ist rollenbasiert,
  der Zustand nicht. Ein zweites `PEER_APPROVAL`-Tool oder eine Umbenennung liefe leer. Fix:
  `CandidateTools.forPeerApproval(ctx)` und `Confirming(offer)` ohne Default; dieselbe
  ArchUnit-Regel wie in A-3 (heute genau zwei Treffer).
- **A-5 (mittel) `demo.mode` wird an neun Stellen einzeln gelesen, mit drei Voreinstellungen.**
  `@Value("\${demo.mode:true}")` (PersonLookupKey, ProductionModeCheck,
  ModuleMigrationLocations), `@Value("\${demo.mode}")` ohne Default
  (KeycloakMigrationRunnerStartup, ToolAvailabilityService), `@ConditionalOnProperty` ohne
  `matchIfMissing` (DemoSurface, KeycloakRealmSessions, FlywayResetConfig), mit `matchIfMissing`
  (DemoDisclosure), `environment.getProperty` (ServerInfoController). Fehlt die Property, ist der
  Zustand widersprüchlich. `PersonLookupKey` im Modul `account` liest sie direkt, obwohl
  `account` keine Abhängigkeit auf `demo_mode` hat. Fix: ein typisiertes `DemoMode`-Bean
  (`@ConfigurationProperties("demo")`) in `demo_mode`; ArchUnit: die Zeichenkette `"demo.mode"`
  nur dort. `PersonLookupKey` verlangt ein Secret und bezieht den Demo-Default aus `demo_mode`.
- **A-6 (mittel) I-19 ist nur gegen `System.out` gesichert.** Siehe S-1. Mechanismus für das
  Register: `type:` (TAN und Codes als `value class` mit `toString() = "***"` in `tool_api`,
  unausdrückbar statt geprüft) und `test:` (Log-Appender-Test).
- **A-7 (niedrig) Doku 08 nennt für `kobil`, `nect`, `personenverzeichnis` weniger
  Abhängigkeiten als der Code.** M15/M17 „einzige Abhängigkeit `texts`“, Code zusätzlich
  `demo_mode`; M5 nennt keine, Code `tool_api`, `texts`, `demo_mode`. Fix: Doku nachziehen;
  Test, der `allowedDependencies` jeder `ModuleMetadata.kt` gegen eine Tabelle in 08 liest
  (wie `InvariantRegisterTest`).
- **A-8 (niedrig) Aufzählungswerte, die nie gespeichert werden.** `ChannelState.REGISTERING`
  steht im persistierten Enum, obwohl I-26 sagt „nie gespeichert“; `JourneyLifecycle.SUCCEEDED`
  und `EXPIRED` sind tot, `RunningJourney.of` und `RetentionJob` müssen sie mitdenken; kein
  CHECK auf `state`/`lifecycle`. Fix: tote Werte entfernen; `REGISTERING` in einen Wire-Typ
  `ShownChannelState`; `sql:ck_channel_session_state`, `sql:ck_auth_journey_lifecycle`, in
  `DatabaseInvariantConstraintTest` geprüft; 02 §Zustände anpassen.
- **A-9 (niedrig) Intent → Kanaltyp ist an zwei Stellen verschieden hart codiert.**
  `AuthIntent.isEntryIntent` enthält `KC_SELECT_METHOD`, `ChannelService` lässt damit
  `POST /app/channels?intent=kc_select_method` zu; `KcChannelService` erlaubt lokal nur
  `KC_SELECT_METHOD`/`REGISTER`. 04 §2 sieht das nicht vor, kein Test deckt es ab. Fix:
  `AuthIntent.entryChannels: Set<ChannelType>`, `fromRequest(value, channel)` prüft; Test über
  `AuthIntent.entries × ChannelType` gegen die Tabelle in 04.
- **A-10 (niedrig) „Nur auf einem AUTHENTICATED-Kanal“ viermal in `ChannelService` kopiert**
  (Logout, Manage, PeerLogin, DeleteAccount); `AuthIntent` dokumentiert die Regel nur im KDoc.
  Fix: `AuthIntent.requiresLoggedInChannel`, einmal in `JourneyService.start` geprüft; Test je
  Intent.
- **A-11 (niedrig) REGISTER hat zwei Zustandswurzeln ohne gemeinsamen Typ.** (Das Experiment
  bleibt.) `JourneyStateCodec` wählt per `catch (InvalidTypeIdException)`,
  `RegisterDispatchStrategy` braucht `else -> error("foreign state")`. Fix: `sealed interface
  RegisterJourneyState` als gemeinsame Wurzel; `rootOf` vollständig, `when` ohne `else`.
- **A-12 (niedrig) Keycloak-Flags im Domain-Vokabular.** `FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY`,
  `KEYCLOAK_LOA1_PASSWORD` („no strategy reads it“); `JourneyContext.featureFlags: Set<String>`.
  Fix: `enum class JourneyFeatureFlag` im Domain, Keycloak-Schalter in `kc`.
- **A-13 (Hinweis) `DemoStepReason` im Fachkern.** Rund 40 Demo-Erklärtexte je Zustand unter
  `domain.journey`; kein Regelwissen (ADR-40). Fix: neben `DemoDisclosure` nach `channel`.
- **A-14 (Hinweis) `require()` → 400 ist Konvention ohne Prüfung.** Jede
  `IllegalArgumentException` aus einer Bibliothek (`UUID.fromString`, `enumValueOf`) wird 400
  „Eingabe ungültig“, auch wenn sie ein Bug ist. Fix: 400 nur für `InvalidInputException`,
  sonst 500 wie `IllegalStateException`.
- **A-15 (Hinweis) Ungenutzte Importe von `IdentityMatchingService` in `account.infrastructure`**
  (`AccountClaim`, `AccountRetraction`, `AccountClaimRepository`). Richtung
  `infrastructure → application` ist falsch, `AccountArchitectureTest` verbietet sie nicht.
  Fix: Importe weg, Regel ergänzen.
- **A-16 (Hinweis) I-10 überwacht nur das Interface.** `IdentityMatchingService.resolve` ist
  öffentlich; innerhalb `account` gilt „nur der Executor fragt“ per Konvention. Fix: `internal`
  oder Regel auf beide Namen.

### 3.4 Codequalität und Tests

- **Q-1 (mittel) Drei Auth-Handler, drei Verhalten für „Enrollment während der ToolSession
  verschwunden“.** `AuthPasswordToolHandler` gibt `Failed.IdentifiedAuth` (kostet Budget und
  zählt als Fehlversuch; dazu `enrollmentRefId!!.toLong()`, einzige Stelle mit `!!` und
  `NumberFormatException`), `AuthDeviceToolHandler` wirft per `checkNotNull` einen 500,
  `AuthSmsToolHandler` ignoriert das Fehlen und meldet `Completed`. Kein Nutzerfehler, also
  keine der drei Antworten richtig. Fix: `UnresolvableReferenceException` wie in `start()`, in
  allen drei Handlern gleich; Handler-Test je Modul „Enrollment weg“.
- **Q-2 (mittel) `JourneyService.applyTransition`: 65 Zeilen `when` mit Rekursion und
  Lifecycle-Mutation.** `JourneyService` (658 Zeilen, 15 Abhängigkeiten) bündelt Lifecycle,
  Transition-Schleife, Budget, Cancellation-Fallout und Keycloak-Sitzungskopplung. Fix: die
  Sitzungskopplung (`openLoginSession`, `keepSessionAlive`, `endWithSession`, `endSession`) in
  `ChannelSessionLifecycle`; `applyTransition` je Variante in private Funktionen, damit die
  Rekursionsstelle (`Perform`) allein sichtbar bleibt.
- **Q-3 (mittel) Tests: Handlung im `then` statt im `when`.** `AccountServiceDbTest` (11 Stellen)
  und `SignInLogDbTest` rufen den Service im `then` auf, ohne `when`; `AuthSmsToolHandlerTest`,
  `AuthDeviceToolHandlerTest`, `AuthPasswordToolHandlerTest` und `AttributeRulesTest` werfen
  per `shouldThrow { handler.start(...) }` im `then` statt `runCatching` im `when` (AGENTS.md
  Testregeln). Die Strategie-Tests halten die Regel ein. Fix: umbauen.
- **Q-4 (mittel) Kern-Orchestrierung ohne dedizierte Unit-Tests; 9 von 22 Handlern nur über
  Integrationstests.** Kein eigener Test für `JourneyService`, `JourneyActionExecutor`,
  `ToolJourneyService`, `KcChannelService`, `ChannelResponseAssembler`, `JourneyContextFactory`,
  `RestoreDataCodec`, die drei Throttle-Services, `LogoutStrategy`, `KcSelectMethodStrategy`,
  `RegisterDispatchStrategy`. Ohne Handler-Test: `AuthEmail*`, `EnrollEmail`, `AuthKobil`,
  `EnrollKobil`, `AuthQr*`, `EnrollQr`, `IdentNect`. Kover-Sperrklinke 82 %, Ist rund 84 %.
  Fix: schmale MockK-Tests für `applyTransition` (Q-2) und `chargeThrottles`; die Throttles und
  `RestoreDataCodec` (Round-Trip, abgelaufen, falsches Secret) direkt; die Lookup- und
  Enroll-Handler nach dem Vorbild `AuthSmsToolHandlerTest`.
- **Q-5 (niedrig) `auth_email` löst das Konto im Handler auf, die Geschwister im Controller.**
  `AuthEmailToolHandler` und `AuthEmailLookupToolHandler` injizieren `AccountDirectory`;
  `AuthSmsToolHandler` sagt ausdrücklich „resolved by the controller“. Fix: Regel in 03 §4
  festlegen und `auth_email` angleichen, oder die Ausnahme (Anker statt `EnrollmentRef`) einmal
  dort begründen.
- **Q-6 (niedrig) Vier QR-Controller ohne OpenAPI-Antwortbeispiele**, alle 18 anderen haben
  zwei bis drei. Fix: angleichen, das Snapshot ist Teil des Vertrags.
- **Q-7 (niedrig) Zwei Wege, das laufende Journey/Kanal-Paar zu laden** (`ToolJourneyService.
  loadContext` und `resolveJourney`/`resolveChannel`), drei fast gleiche `ChannelResponse(...)`.
  Fix: eine private `resolve(context)` und `responseAssembler.respond(...)` mit `demo`.
- **Q-8 (niedrig) `!!` an 21 Stellen, meist auf JPA-Feldern nach impliziter Invariante**, etwa
  `ChannelService:175` zwei Zeilen unter dem `checkNotNull`, das dieselbe Invariante prüft. Fix:
  `LiveChannel`-Muster ausbauen (`AuthenticatedAppChannel(session, authContextId)` aus
  `requireAuthenticatedApp`); `ToolAvailability` mit non-null Konstruktorparametern; Rest
  `checkNotNull { … }` mit Kontext.
- **Q-9 (niedrig) `RestoreDataCodec` fängt `Exception` und liefert stumm `null`.** Ein defektes
  Shared-Secret bleibt unsichtbar. Fix: nur `ParseException`/`JOSEException`, auf INFO loggen.
- **Q-10 (niedrig) `else ->` bei sealed-Subjekten.** `AuthQrLookupToolHandler.read` fasst zwei
  Varianten als `else -> CLOSED`; eine dritte würde still „closed“. Ebenso in `IdentFscFlow`,
  `IdentEidFlow`, `QrLoginBrowserSide`. Fix: Zweige ausschreiben.
- **Q-11 (niedrig) Umlaute in `Text("…")` gemischt** (80× „ae/oe/ue“, 110× echte Umlaute); zwei
  Schreibweisen sind zwei Schlüsselstile im Bundle. Fix: einmal normalisieren,
  `/translate-texts`, Test in `TextCatalogTest` gegen `ae|oe|ue` mit Allowlist.
- **Q-12 (niedrig) Tote Deklarationen.** `AuthEvidence.acrFor(method)`,
  `SessionManagementService.bindAccountAndAuthContext`, `AuthJourneyRepository.
  deleteByConsumedAtBeforeOrExpiresAtBefore` und `deleteByChannelSessionIdIn`,
  `Personenverzeichnis.findPersonByKvnr`. Fix: löschen.
- **Q-13 (niedrig) `IntegrationTestSupport` als Gott-Helfer** (424 Zeilen, 33 Methoden),
  43 Testklassen flach in `core/orchestrator`; Testhelfer mehrfach definiert (`signDeviceProof`,
  `enrollDevice`, `kcPost`, `stubAssertion` je 3–4×). Fix: `HttpSupport`, `DpopStub`,
  `JourneyFlows` trennen, Helfer nach `support/`, Integrationstests in ein Unterpaket.
- **Q-14 (Hinweis) Kommentare.** 22 KDoc-Blöcke über der Fünf-Zeilen-Regel
  (`ModuleMetadata.kt` 18 Zeilen Lesepfad, `JourneyService` 17 Zeilen Phasentabelle, `AuthIntent`,
  `AccountRules`, `AccountService`); 34 deutsche Kommentarzeilen neben 2 300 englischen (vor
  allem `kc/*`); der A11-Satz 35× kopiert. Positiv: kein TODO/FIXME, keine Geschichte, kein
  auskommentierter Code. Fix: Lesepfad und Phasentabelle in 08/ADR-40 verlinken; Kommentarsprache
  Englisch; A11 nur als Link.
- **Q-15 (Hinweis) Deutsche Bezeichner im `contract`-Modul.** `Partnernr` (Wertobjekt,
  `findPersonIdByPartnernr`), `Interessent` in `ident_eid`. Die Regel erlaubt Deutsch nur fürs
  Fremdsystem selbst; `Partnernr` sieht jedes Tool. Fix: `PartnerNumber`, `Prospect`; Wire-Namen
  bleiben. Gehört zu `DPoP-demo-tcwr`.
- **Q-16 (Hinweis) `relaxed = true` 115×**, auch wo der Mock das Prüfobjekt ist. Fix: nur für
  Nebenrollen.
- **Q-17 (Hinweis) Kein `allWarningsAsErrors`, kein detekt/ktlint.** Bei 0 Warnungen
  (`:compileKotlin --rerun`) ist `allWarningsAsErrors = true` ein kostenloser Schutz.

## 4. Reihenfolge

Grundsatz wie bisher: zuerst, was ein Sicherheitsexperte in der ersten Stunde findet; dann die
Prüfregeln, damit die Architektur ihre Konventionen selbst hält; dann Verhalten; dann Aufräumen.

**Phase M – Zweitlinien schließen**

1. S-2 Sperrzähler verfällt; Admin-Drossel mit IP. Test.
2. K-1 Peer-Auth-Schlüssel als Secret-Property oder Realm-Key. Test.
3. K-2 Authenticator: `next == null` ist ein Fehler, Konto und Niveau selbst prüfen. Tests.
4. K-4 Timeouts Richtung Keycloak (beide Seiten), K-11 ein Client. K-6 `isValid` wirft.
5. S-1 Rohwerte aus den Meldungen der Wertobjekte, Lookup-Controller validieren vorher; K-3
   Texte über `OrchestratorClient`; K-15 Whitelist im Grant; S-5 neutraler 401-Text.
6. K-7 Realm: `loa-max-age`, Lebensdauern, `sslRequired` in V1.

**Phase N – Prüfregeln dichten**

7. A-6 I-19: Log-Appender-Test und `Secret`-Wertklasse; Register nachziehen.
8. A-2 Domain-Regel als Positivliste; K-5 Transaktionsregel auf Methoden und alle Netzklassen;
   A-15/A-16 `AccountArchitectureTest` ergänzen.
9. A-1 `descriptorOf(method, role)`; A-3/A-4 keine `ToolId`-Literale im Orchestrator
   (`MgmtPasswordController` nach `auth_password`, `Confirming` ohne Default).
10. A-5 `DemoMode`-Bean, eine Stelle; A-7 Modultabelle in 08 per Test gegen die Metadaten.
11. A-9/A-10 Intent deklariert Kanäle und Anmeldepflicht; A-8 tote Enum-Werte, CHECKs.

**Phase O – Verhalten und Tests**

12. Q-1 „Enrollment weg“ eine Lesart, drei Tests; Q-5 auth_email angleichen oder begründen.
13. Q-3 Testregeln (`when`/`then`) in den sechs Dateien; Q-4 fehlende Unit-Tests; K-12
    Dispatcher-Tests in der Erweiterung.
14. Q-2 `ChannelSessionLifecycle` aus `JourneyService`; Q-7 `ToolJourneyService` entdoppeln.

**Phase P – Aufräumen**

15. Q-8 `!!`, Q-9 Catch-all, Q-10 `else`, Q-12 Totes, Q-11 Umlaute, Q-14 Kommentare, Q-15 Namen,
    Q-17 `allWarningsAsErrors`; A-11/A-12/A-13 Zuschnitt im Domain.
16. S-4/S-12/S-13/S-9/K-13 in 07-betrieb: Forward-Header, Image-Pinning, Swagger außerhalb Demo,
    Keycloak-Admin-Passwort, Rotationsablauf.

Nicht in dieser Reihenfolge, weil schon offen: `DPoP-demo-61kp` (Schlüsselverwaltung),
`DPoP-demo-bo1w` (Verschlüsselung), `DPoP-demo-9msv`/`x25a`/`ai4x`/`dm2j` (Umgebung),
`DPoP-demo-36xz` (Lookup-Orakel), `DPoP-demo-xzl1` (Clock), `DPoP-demo-d6za`/`df48` (Tests),
`DPoP-demo-hwc6` (I-14), `DPoP-demo-oe06` (I-23), `DPoP-demo-tcwr` (Namen).

## 5. Stand der Umsetzung (2026-09-28)

Alle Befunde der Stufe „mittel“ sind bearbeitet; einen hohen gab es nicht. Je Punkt: was getan
wurde, wo es abgesichert ist, und der Commit. Entscheidungen des Inhabers vorab: S-2 ohne IP im
Schlüssel, A-3 ganz nach `auth_password`, A-5 ohne neue Modulkante, Q-4 Kern und Handler.

**Sicherheit**

- ~~S-1 Nutzereingaben im Log~~ – erledigt (`d1dbd5ba`): Die Wertobjekte nennen den Wert nicht
  mehr, der 400-Handler loggt nur Typ und Ort. Zusammen mit A-6.
- ~~S-2 Sperrzähler verfällt nie~~ – erledigt (`ad2d6b11`): Nach abgelaufener Sperre beginnt der
  nächste Fehlversuch bei eins, im selben `UPDATE`. `AttemptLockoutExpiryDbTest` prüft das gegen das
  echte Statement. Keine IP im Admin-Schlüssel (Entscheidung): Hinter der Route wäre es die
  Proxy-IP, solange S-4 offen ist.

**Keycloak-Erweiterung und -Anbindung**

- ~~K-1 Peer-Auth-Schlüssel lesbar~~ – erledigt (`afbb965b`): als `PASSWORD` mit `secret=true`
  deklariert. `OrchestratorSettingsTest` prüft die Maskierung mit Keycloaks eigenem
  `StripSecretsUtils` (ohne die Deklaration rot, geprüft). Der Befund aus Abschnitt 8 („nicht
  verifiziert“) ist damit am Keycloak-Code bestätigt.
- ~~K-2 Authenticator fail-open~~ – erledigt (`58bf00ca`): `LoginCompletion` als reine Funktion.
  Fertig nur bei `next=authenticated` oder Kanal `AUTHENTICATED`, dann nur mit demselben Konto und
  mindestens dem `targetAcr` der Ausführung. Eine Antwort ohne `next` ist nur auf einem schon
  angemeldeten Kanal gültig: So antwortet der Orchestrator, wenn das Niveau bereits reicht.
- ~~K-3 Textbundle ungesichert~~ – erledigt (`428e8317`): über `OrchestratorClient.texts`,
  signiert und geprüft, auch für `304`. TLS auf dem Hop bleibt Umgebung (ADR-35).
- ~~K-4 keine Timeouts~~ – erledigt (`3d034114`), mit K-11: Connect 3 s, Read 10 s auf beiden
  Seiten, ein gemeinsamer `HttpClient` in der Erweiterung. `KeycloakHttpTest` mit stummem Server.

**Architektur und Konzept**

- ~~A-1 Descriptor nach `method`~~ – erledigt (`e85598a7`): `ToolCatalog.descriptorOf(method, role)`,
  Test mit beiden Bean-Reihenfolgen.
- ~~A-2 Domain-Regel~~ – erledigt (`9bdcf408`), mit A-15: Positivliste `AccountProfile`,
  `AuthMethodView`; `io.swagger`/`java.util.logging` verboten; `account.infrastructure` darf nicht
  auf `application` zeigen.
- ~~A-3 `MgmtPasswordController` im Orchestrator~~ – erledigt (`91de465f`): im Modul
  `auth_password`, nur über `tool_api`. Neu: `@BindingKey(keycloakOnly = true)` (Resolver lehnt
  DPoP ab, Spec nennt nur `kc-peer-auth`), Port `KeycloakToolCalls` (prüft den Anker, bucht wie eine
  Journey), `InvalidStateException` für `409`. Vertrag kompatibel.
- ~~A-4 `ToolId` im Zustand~~ – erledigt (`82e8aaf8`): `CandidateTools.forPeerApproval`.
  `CoreNamesNoToolTest` hält den Kern frei von `ToolId`-Literalen (A-3 und A-4).
- ~~A-5 `demo.mode` neunmal~~ – erledigt (`f78a122f`): `DemoMode`, `OnlyInDemoMode`,
  `OutsideDemoMode` in `demo_mode`; `DemoModeSwitchTest` verbietet jeden anderen Leser. `account`
  kennt den Demomodus nicht mehr: Es meldet nur (`ChangeLogLookupKeys`), `ProductionModeCheck`
  entscheidet.
- ~~A-6 I-19 nur gegen `println`~~ – erledigt (`d1dbd5ba`): `NoSecretsInLogIntegrationTest` fängt
  alle Log-Ereignisse eines Versand- und Prüfdurchlaufs ab (mit dem alten Verhalten rot, geprüft);
  Register nachgezogen. Nicht umgesetzt: eine `Secret`-Wertklasse für Codes. Der Test sichert
  dieselbe Aussage, die Wertklasse wäre ein eigener Umbau aller Code-Pfade.

**Codequalität und Tests**

- ~~Q-1 drei Verhalten bei verschwundenem Enrollment~~ – erledigt (`16a77b34`): überall
  `UnresolvableReferenceException` (422), nichts zählt, niemand wird angemeldet. Je Handler ein Test.
- ~~Q-2 `applyTransition` und `JourneyService`~~ – erledigt (`a8de0ea0`): `ChannelSessionLifecycle`
  trägt die Keycloak-Sitzung, `applyTransition` verteilt nur noch (18 statt 65 Zeilen).
- ~~Q-3 Testregeln~~ – erledigt (`16a77b34`, `57dc6028`): die drei Handler-Tests,
  `AttributeRulesTest`, `AccountServiceDbTest` (rund 30 Blöcke, nicht 11) und `SignInLogDbTest`.
  Dabei gefunden: Ein `beforeEach` läuft in Kotest nur vor den `then`-Blättern, also nach einer
  Handlung im `when`. Resets, die bisher in `beforeEach` standen, stehen jetzt im `when`.
- ~~Q-4 fehlende Unit-Tests~~ – erledigt (`4edb021e`): 19 neue Testklassen, 166 Fälle. Die drei
  Sperr- und Drosseldienste, `RestoreDataCodec`, `ChannelSessionLifecycle`,
  `KeycloakToolCallsService`, `LogoutStrategy`, `KcSelectMethodStrategy`, `RegisterDispatchStrategy`
  und die neun Handler ohne Test. Abweichung vom Vorschlag: `applyTransition` und `chargeThrottles`
  bekommen keinen MockK-Test. Mit 13 bzw. 10 gemockten Abhängigkeiten nagelte er die
  Implementierung fest (Q-16). `ModelBasedJourneyTest` und die Sperr-Integrationstests decken sie,
  die herausgelösten Klassen sind einzeln getestet. Dabei gefunden: `auth-kobil` antwortete bei
  fehlendem Enrollment mit 500, jetzt 422 wie die übrigen Verfahren (Q-1).

**Nebenbei erledigt:** K-11 (mit K-4), A-15 (mit A-2).

**Offen:** die Befunde der Stufen „niedrig“ und „Hinweis“, je ein bd-Issue unter dem Epic
`DPoP-demo-9ppv`. Ohne eigenes Issue, weil schon anderweitig offen: S-4 (`DPoP-demo-ai4x`,
`DPoP-demo-dm2j`), S-6 (`DPoP-demo-bo1w`, `DPoP-demo-61kp`), K-10 (`DPoP-demo-9msv`,
`DPoP-demo-x25a`), K-13 (`DPoP-demo-61kp`), Q-15 (`DPoP-demo-tcwr`). Ohne Handlungsbedarf, weil
bewusst so entschieden: S-8, S-11.

## 6. Geprüft und in Ordnung

Was die Prüfungen gelesen und für tragfähig befunden haben, damit der Bericht nicht nur Lücken
nennt:

- **DPoP.** `typ=dpop+jwt`, nur ES256/384/512 mit EC-Schlüssel, privater JWK abgelehnt,
  `htm`/`htu`/`iat`/`jti` Pflicht, Fenster 120 s + 30 s Skew, Replay per Primärschlüssel-INSERT
  in eigener Transaktion, RFC-7638-Thumbprint; Geräte-Proofs mit eigenem `typ` und Gerätekey ≠
  Kanalkey. Fehlender Proof ist 401.
- **Kanalbindung.** `@BindingKey` als Argument-Resolver, `ApiBoundaryArchitectureTest` als
  Default-Deny mit benannter Ausnahmeliste; `ChannelAccessGuard` vergleicht in konstanter Zeit;
  Web-Kanal: Anker = eigene Kanal-Id, RestoreData an `kcSessionId` gebunden, fail-closed.
- **Autorisierung in Journeys.** `IdentityResolver`, `absorbProvisionalAccount`,
  `linkDeviceToAccount` nur aus dem Executor (ArchUnit über die ganze Anwendung);
  `accountOfProof` verhindert Kontowechsel mid-journey; `checkCorrelation` und
  `attestedIdentityMatches` verhindern „fremde KVNR eintippen“; MANAGE, DELETE und
  CONFIRM_PEER_LOGIN verlangen loa2 mit frischem Nachweis; `removeMethod` schützt vor
  Selbstaussperrung; `LiveChannel`/`RunningJourney` und die CHECK-Constraints sichern I-1 bis I-4.
- **Codes und Geheimnisse.** SecureRandom überall; TAN und Codes nur als HMAC-Hash, 5 Minuten,
  Vergleich per `MessageDigest.isEqual`; Enumeration-Schutz in allen Lookup-Tools (unbekannt,
  gesperrt, Budget erschöpft antworten gleich, `attemptedAccountId` zählt trotzdem);
  Versandbudgets je Adresse; Zähler atomar per UPDATE; Argon2id mit konstantem Aufwand auch für
  den Keycloak-nativen Pfad.
- **Keycloak-Grant und Peer-Auth.** `private_key_jwt` vor `process`; öffentliche Clients
  gesperrt; `session_id` nur für gültige, eigene Sitzung desselben Nutzers mit Marker-Note
  (I-24); Antworten an `req`, `status`, `body_sha256` gebunden und vor jeder Verwendung geprüft.
  Browser-Flow: Formulare auf `url.loginAction` (Keycloaks `session_code` als CSRF-Schutz),
  Schrittzustand nur in AuthNotes, kein Open Redirect, Templates auto-escaped.
- **UserStorageProvider (ADR-38).** Kein Import, keine Listen, Id `f:orch-accounts:<accountId>`
  berechnet (I-15), Attribute read-only, Löschung über Admin-Endpunkt mit `manage-users`.
  Migrations-Client `client-jwt`, Admin-Rolle wird aktiv entzogen. Keine festen Secrets in
  Erweiterung oder Migrationen. Native SPI, kein eigener ServiceLoader.
- **Realm (V1–V4).** Browser-Client public mit PKCE S256, keine Direct Access Grants, kein
  Implicit Flow; Admin- und Token-Clients vertraulich mit `client-jwt`; Brute-Force-Schutz an.
- **Modulgrenzen und Domain.** `allowedDependencies` aller Tool-Module deckungsgleich mit 03 §4;
  benannte Kanten in `SimulationBoundaryArchitectureTest` mit Begründung; `orchestrator.domain`
  und `account.domain` importieren tatsächlich nur `tool_api`, `texts`, `AccountProfile`;
  Domain-Pakete zyklenfrei; vier Phasen sauber getrennt (Strategien liefern nur `Transition`,
  Executor ruft nie zurück, Routing ist reine Funktion); alle `when` über `Transition`, `Action`,
  `ToolOutcome`, `MethodRole` erschöpfend.
- **Persistenz (ADR-16).** Alle Tabellen schema-qualifiziert, keine Fremdschlüssel über
  Schemagrenzen, Kopplungen als indizierte Spalten dokumentiert und per `EnrollmentCleanup`/
  `AccountDeletionService` bereinigt; `ToolSessionCoverageTest` je Modul. Retention in Stapeln
  mit Metriken; Änderungsprotokoll ohne Werte, 10 Jahre, HMAC-Suchschlüssel mit Rotation.
- **Fehlermodell.** Ein `@RestControllerAdvice`, `ErrorCode` trägt den Status ohne Spring,
  alle Codes erreichbar, `Exception`-Fallback mit Cause-Suche, 500 mit neutralem Text.
- **Codequalität.** 0 Compiler-Warnungen; nur Konstruktor-Injection; `lateinit` 2×; `var` nur in
  Entitäten; keine Entität in einem DTO; `@Transactional` mit `noRollbackFor` (ADR-43)
  konsequent; `readOnly` auf Lesern; kein TODO/FIXME; Versionen zentral und aktuell
  (Spring Boot 4.1.0, Kotlin 2.4.0, nimbus 10.10, BouncyCastle 1.81).
- **Tests.** 122 `BehaviorSpec`, 13 Spring-Kontext-Tests mit einer Konfiguration (Caching
  greift), keine festen Ports, `eventually` nur für asynchrone Ereignisse, kein `mockkStatic`.

## 7. Kennzahlen

- Kotlin-Dateien main / test: 474 / 176; Zeilen 30 867 / 22 008.
- Keycloak-Erweiterung: 99 Dateien main, 14 Testklassen.
- Größte Klassen: `JourneyService` 658, `AccountService` 485, `ChannelController` 482 (davon
  rund 350 OpenAPI-Beispiele), `JourneyActionExecutor` 424, `ChannelService` 402.
- Funktionen über 60 Zeilen: 1 (`applyTransition`, 65). `!!`: 21. `runCatching`: 22.
- Compiler-Warnungen: 0. Kover-Sperrklinke: 82 %.

## 8. Nicht verifiziert

- Ob Keycloaks `StripSecretsUtils` undeklarierte Config-Keys wirklich unmaskiert ausliefert
  (K-1): nach Quellcode ja, im laufenden System nicht geprüft.
- Zeitverhalten der Lookup-Pfade (Argon2-Dummy gegen echten Hash) und des nativen
  Passwortformulars („unbekannt“ gegen „falsch“) als Enumerationskanal: am Code plausibel,
  nicht gemessen.
- Verhalten der H2-Konsole (`web-allow-others`) zur Laufzeit: nur die Konfiguration gelesen.
