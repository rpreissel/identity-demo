# Audit 2026-10-09: Sicherheit, Architektur, Codequalität

Dieses Audit prüft den Stand `bf05d08`. Der Schwerpunkt liegt auf den Änderungen seit dem
Sicherheitsaudit vom 2026-10-03:

- ADR-53 bis ADR-56: Verschlüsselung und Schlüsseldienst,
- ADR-57: Keycloakify als einziges Login-Theme,
- ADR-58: Keycloak führt keine eigenen Anmeldeschritte,
- ADR-59: Nachweise je Keycloak-Sitzung im Orchestrator.

Daneben sucht es im ganzen Code nach Vereinfachungen, die ihn leichter lesbar und einheitlicher
machen.

Befunde, die schon in [offene-befunde.md](offene-befunde.md) stehen, werden hier nicht wiederholt.
Abschnitt 5 nennt aber die Einträge dort, die inzwischen falsch beschrieben oder erledigt sind.

So ist dieses Dokument zu lesen:

- **Kürzel:** `AU-n` steht für einen Befund zu Sicherheit oder Architektur. `V-Bn`, `V-En` und
  `V-Fn` stehen für Vereinfachungen im Backend, in der Keycloak-Erweiterung und im Frontend
  einschließlich Theme.
- **Schwere:** wie in [offene-befunde.md](offene-befunde.md) (mittel, niedrig, Hinweis).
- **Prüfung:** Jeder Befund ist am Code nachvollzogen. Am laufenden System ist keiner
  reproduziert. Bei AU-1 und AU-3 gehört das vor die Behebung.
- **Issues:** Alle Befunde hängen am Epic `DPoP-demo-8x0p`.
- **Stand der Behebung:** Abschnitt 8 führt auf, was seit dem Audit behoben ist. Bei jedem
  behobenen Befund steht „behoben“ in der Überschrift.

---

## 1. Ergebnis in Kürze

ADR-58 und ADR-59 sind konsequent umgesetzt. `RestoreDataCodec`, `KeycloakToolCalls`, der
Update-Authenticator, die Passwort-Executions im Realm und die Rolle der Federation als
Passwortprüfer sind vollständig entfernt. Die Kapitel 04, 06, 09, `journeys/`, das Glossar und die
Invarianten sind nachgezogen. Die Modulgrenzen halten, und die Tool-Module sind sehr gleichförmig
gebaut.

Handlungsbedarf besteht vor allem an der neuen Tabelle `orchestrator.keycloak_session_evidence`
(ADR-59). Der Code nimmt an, dass alle Zeilen einer Keycloak-Sitzung demselben Konto gehören und
nur aktive Verfahren nennen. Erzwungen wurde beides nicht (AU-1, AU-2). AU-1 ist inzwischen
behoben. Dazu kommen zwei Folgen von
ADR-58 auf der Passwortseite (AU-9, AU-10).

Beim Code fallen drei Muster auf:

- **Kopien zwischen Tools:** Einmalcodes, Controller und Cleanups sind je Tool neu geschrieben,
  ebenso die Formulare im Frontend.
- **Zwei Dispatch-Schleifen in der Erweiterung:** Authenticator und Required Action führen dieselbe
  Schleife getrennt aus.
- **Toter Code:** einige Deklarationen ohne Nutzer, die mit dem Umbau übrig geblieben sind.

## 2. Sicherheit

### AU-1 (mittel, behoben) Nachweise eines fremden Kontos können einen Step-up erfüllen

`DPoP-demo-8x0p.1`

Die Prüfung, wem eine Zeile in `keycloak_session_evidence` gehört, fehlte an drei Stellen:

1. **Melden.** `OrchestratorNotes.reportFlowEnd`
   (`keycloak-extension/…/login/OrchestratorNotes.java:121`) meldet die Sitzung, auf die das
   Identity-Cookie *am Ende* des Durchlaufs zeigt. Ob deren Nutzer der angemeldete Nutzer des
   Durchlaufs ist, prüft es nicht. Keycloak lehnt einen anderen Nutzer erst in `attachSession` ab,
   also nach `onTopFlowSuccess`. Bis dahin ist die Meldung schon geschrieben.
2. **Schreiben.** `KeycloakChannelService.flowEnded` (`KeycloakChannelService.kt:209`) schreibt
   unter dieser `kcSessionId`, ohne vorhandene Zeilen zu prüfen. `updateIfYounger`
   (`KeycloakSessionEvidenceRepository.kt:50`) überschreibt sogar `account_id`.
3. **Lesen.** `upsertChannel` (`KeycloakChannelService.kt:125`) vergleicht das Konto nur an
   `restored.firstOrNull()`. `findLive` hat kein `ORDER BY`. `ApplyRestoredEvidence` übernimmt
   danach alle Zeilen.

**Szenario:** Ein Angreifer kennt das Passwort von A und hat ein eigenes Konto B mit einem
`loa2`-Verfahren. In einem Tab meldet er sich als A an (Sitzung S). In einem zweiten Tab, den er
vorher ohne Cookie geöffnet hat, schließt er die Anmeldung als B mit `acr_values=2` ab.

`flow-end` schreibt die Nachweise von B unter S. Ein Step-up von A in S übernimmt sie, wenn die
erste gelesene Zeile A gehört. A erhält dann `acr=loa2` ohne eigenen zweiten Faktor. Auch ohne
Angreifer vermischt ein Kontowechsel in zwei Tabs eines Browsers die Nachweise.

**Behebung (2026-10-10):** An allen drei Stellen wird jetzt geprüft.

- **Melden:** `OrchestratorNotes.reportFlowEnd` meldet eine vorhandene Sitzung nur, wenn ihr
  Nutzer der angemeldete Nutzer des Durchlaufs ist (`sessionBelongsToRun`). Sonst meldet es nichts.
  Ohne Cookie legt Keycloak die Sitzung für diesen Durchlauf an; sie wird wie bisher gemeldet.
- **Schreiben:** `flowEnded` lehnt eine Sitzung mit Zeilen eines anderen Kontos mit `409` ab
  (`KeycloakSessionEvidenceRepository.holdsOtherAccount`). Der Kanal behält dann auch seine alte
  Sitzungs-Id. `updateIfYounger` ändert nur noch Zeilen desselben Kontos und setzt `account_id` nicht
  mehr.
- **Lesen:** `upsertChannel` lehnt Zeilen mehrerer Konten mit `409` ab, statt sie zu filtern. Welche
  davon richtig wären, ist nicht bekannt.
- **Tests:**
  - `FlowEndSessionTest` in der Erweiterung prüft die Entscheidung beim Melden.
  - Zwei neue Fälle in `KeycloakChannelIntegrationTest`: `flow-end` eines zweiten Kontos in eine
    fremde Sitzung, und eine Sitzung mit Zeilen zweier Konten.
- **Doku:** nachgezogen in ADR-59, [05-api.md](05-api.md) Abschnitt 3b, im Lesepfad Sicherheit und
  bei I-5 in den Invarianten.

Ein Rest bleibt: Zwei Konten, die gleichzeitig in eine bis dahin leere Sitzung schreiben, kommen
beide an der Prüfung beim Schreiben vorbei. Dann greift die Prüfung beim Lesen und verweigert die
Übernahme. Den Fall kann nur ein fehlerhafter Aufrufer auslösen, denn eine Keycloak-Sitzung gehört
genau einem Nutzer. Am laufenden System mit zwei Tabs ist die Behebung nicht geprüft.

### AU-2 (niedrig, behoben) Ein widerrufenes Verfahren kommt über `flow-end` zurück

`DPoP-demo-8x0p.2`

`AccountDeletionService.revokeMethod` (`AccountDeletionService.kt:87`) löscht die Zeilen des
Verfahrens in allen Sitzungen des Kontos. Die Nachweise laufender Kanäle (`SessionEvidenceRecord`)
bleiben aber stehen.

`flowEnded` holt den Kanal über `requireChannel`, nicht über `LiveChannel`, nimmt also auch beendete
Kanäle an. Es schreibt alle Verfahren des Kanals, ohne sie mit den aktiven Verfahren des Kontos
abzugleichen. Ein Tab, der nach dem Widerruf fertig wird, legt die Zeile mit altem `proven_at`
wieder an. Jeder spätere Durchlauf in dieser Sitzung übernimmt sie, bei `loa1` bis zum
Sitzungsende.

**Vorschlag:**

- Beim Übernehmen in `upsertChannel` nur Zeilen verwenden, deren Verfahren beim Konto noch aktiv ist.
  Das deckt jeden Wettlauf ab.
- Zusätzlich `flowEnded` für einen beendeten Kanal ablehnen und nicht mehr aktive Verfahren
  überspringen.
- Ein Test „Widerruf, danach `flow-end` eines offenen Tabs“.

**Behebung (2026-10-10):** wie vorgeschlagen.

- `upsertChannel` übernimmt nur Nachweise von Verfahren, die beim Konto noch aktiv sind.
  Identifizierungen (Achse `IDENTITY`) sind keine Verfahren und bleiben.
- `flowEnded` lehnt einen beendeten Kanal ab (`LiveChannel`, `409`) und schreibt nur Verfahren, die
  noch aktiv sind.
- Zwei neue Fälle in `KeycloakChannelIntegrationTest`: ein Tab, der nach dem Widerruf endet, und
  eine verwaiste Zeile eines widerrufenen Verfahrens.

### AU-3 (Hinweis) „Verfahren verwalten“ meldet seine Nachweise nicht

`DPoP-demo-8x0p.3`

`reportFlowEnd` läuft nur in `OrchestratorResumeAuthenticator.onTopFlowSuccess`, also bevor Keycloak
Required Actions ausführt. `OrchestratorManageMethodsRequiredAction` macht auf demselben Kanal
danach einen Step-up auf `loa2`. Diese Nachweise erreichen `keycloak_session_evidence` nie. Das
widerspricht ADR-59, wonach jeder Durchlauf seine Nachweise meldet.

**Vorschlag:** Beim Abschluss der Required Action noch einmal `reportFlowEnd` rufen; der Aufruf ist
idempotent. Alternativ „Verfahren verwalten“ als Intent über den `OrchestratorAuthenticator` führen
(siehe V-E1). Damit ist auch K-3 gelöst.

### AU-4 bis AU-8 (Hinweise, behoben bis auf einen Teil von AU-7) Kleine Härtungen

`DPoP-demo-8x0p.4`

- **AU-4 Ein Nachweis ohne Alter wird frisch.** `MethodEvidenceRow.of`
  (`KeycloakSessionEvidenceInitializer.kt:50`) setzt `provenAt ?: now`. Ein Nachweis mit unbekanntem
  Alter zählt nur bis `loa1`. Nach dem Ablegen gilt er 30 Minuten als frisch. Das widerspricht
  [invarianten.md](invarianten.md) („behält seinen alten Zeitstempel“). Betroffen sind nur
  Altdaten. Vorschlag: solche Nachweise nicht ablegen oder die Spalte nullable machen.
- **AU-5 Zu breiter `catch` in `flowEnded`.** `DataIntegrityViolationException` fängt auch eine zu
  lange `kcSessionId` (Spalte `VARCHAR(64)`, SQLState 22001). Der Nachweis geht dann still
  verloren. Vorschlag: die Länge im Controller mit `400` ablehnen und nur `DuplicateKeyException`
  fangen.
- **AU-6 `masterKey()` auch auf dem Lesepfad.** `ToolContext.masterKey()` (`ToolJourney.kt:51`)
  steht auch dem Kontext zur Verfügung, den `loadContext` für GET liefert. Er kann damit einen
  Journey-Schlüssel anlegen. Die Enroll-SMS-Controller rufen ihn schon beim ersten PATCH, nicht
  erst bei `Complete`. Vorschlag: nur am `AuthorizedToolContext` anbieten und erst beim Abschluss
  abfragen.
- **AU-7 AAD ohne Zeilenbezug.** Die Zusatzdaten `key:$keyId:$purpose` (`ClaimCrypto.kt:132`)
  binden an Schlüssel und Zweck, nicht an die Zeile. Wer in die Datenbank schreiben kann, kann
  innerhalb eines Kontos versiegelte Werte desselben Zwecks tauschen, etwa Refresh-Tokens zweier
  App-Sitzungen. Vorschlag: die Zeilen-Id aufnehmen, wo sie beim Versiegeln feststeht.
- **AU-8 Keycloak kann wieder lokal ein Passwort speichern.** Seit ADR-58 ist die Federation kein
  `CredentialInputUpdater` mehr. „Reset password“ in der Admin-Konsole legt dann still ein
  Keycloak-Passwort an. Nutzbar ist es nicht: Es gibt kein Passwortformular, kein Direct Grant und
  keine Account-Konsole. [16-lesepfad-sicherheit.md](16-lesepfad-sicherheit.md) behauptet aber
  noch, die Federation lehne jede Passwortänderung ab. Vorschlag: einen minimalen Updater behalten,
  der `ReadOnlyException` wirft, oder den Lesepfad korrigieren.

**Behebung (2026-10-10):**

- **AU-4:** `flowEnded` legt Nachweise ohne Zeitpunkt nicht ab. `MethodEvidenceRow.of` verlangt
  einen Zeitpunkt.
- **AU-5:** `kcSessionId` wird an `upsertChannel` und `flowEnded` geprüft: leer oder länger als
  die Spalte ergibt `400` (Test in `KeycloakChannelIntegrationTest`). Der `catch` bleibt, er
  kann jetzt nur noch eine Zeile treffen, die ein anderer Tab geschrieben hat.
- **AU-6:** `masterKey()` steht nur noch am `AuthorizedToolContext`. Die Einschreibungen von SMS
  und KOBIL fragen ihn erst beim Speichern ab (`masterKey: () -> MasterKeyId`). Ein Abbruch nach
  der Eingabe der Rufnummer legt deshalb keinen Schlüssel mehr an
  (`RegisterEnrollFirstFlowIntegrationTest`).
- **AU-7, für die App-Tokens:** Die Zusatzdaten enthalten die Id der App-Sitzung. Ein Token, das
  sich so nicht öffnen lässt, gilt als nicht vorhanden; die Sitzung holt ein neues bei Keycloak.
  Das gilt auch für Tokens, die vor der Änderung versiegelt wurden (Test in
  `WorkingDataEncryptionDbTest`).
- **AU-7, offen für Rufnummer und KOBIL-PIN:** Beim Versiegeln steht die Zeilen-Id noch nicht
  fest, und vorhandene Einschreibungen ließen sich nach einem Formatwechsel nicht mehr öffnen. Das
  braucht eine Umstellung mit Migration der vorhandenen Zeilen.
- **AU-8:** Die Federation ist wieder `CredentialInputUpdater` und lehnt jedes Credential mit
  `ReadOnlyException` ab (`OrchestratorStorageProviderTest`). Lesepfad und ADR-58 sind angepasst.

## 3. Architektur

### AU-9 (niedrig) ADR-58 auf der Passwortseite nicht ganz umgesetzt

`DPoP-demo-8x0p.5`

ADR-58 verlangt im Abschnitt „Preis“, dass die Tool-Seite kann, was Keycloaks Passwortformular
konnte: das Vorbelegen über `login_hint` und ein Markup, das Passwort-Manager erkennen. Beides
fehlt:

- `keycloak-theme/src/login/components/Field.tsx:8` setzt für jedes Feld `autoComplete="off"`.
- `login_hint` wertet weder die Erweiterung noch das Theme aus.

**Vorschlag:**

- Die Felder auszeichnen: `username`/`email`, `current-password` und `new-password`.
- Der Renderer von `auth-password-lookup` belegt `email` aus `login_hint` vor.

### AU-10 (niedrig) SA-27 ist neu zu bewerten

`DPoP-demo-8x0p.6`

SA-27 ist als „bewusst“ eingestuft und soll nachgeholt werden, „bevor eine Passwortanmeldung per
Lookup produktiv geht“. Mit ADR-58 ist diese Bedingung eingetreten:

- `auth-password-lookup` ist der einzige Passwortweg der Website.
- Keycloaks Brute-Force-Schutz ist aus (`V1__realm.kc.kts:24`).

Damit bleibt als Grenze nur die Kontosperre, die vor dem Versuch prüft und erst danach zählt.
Vorschlag: den Versuch vorab buchen (`DPoP-demo-164n.29`) und die Stellen in
[offene-befunde.md](offene-befunde.md) Abschnitt 6, [07-betrieb.md](07-betrieb.md) Abschnitt 4 und
im Lesepfad angleichen.

### AU-11 (Hinweis) Reste des alten Wegs in Doku und Kommentaren

`DPoP-demo-8x0p.7`

- **Doku:**
  - [05-api.md](05-api.md) nennt `SessionEvidenceService.applyEvidenceUpdate`, das entfallen ist.
  - [verfahren/password.md](verfahren/password.md) sagt „prüft oder ersetzt“. Ersetzt wird nur noch
    in Tests (`PasswordCredentialPort.setNew`).
  - ADR-026 („Folgen“) nennt `restoreData` und die Passwortprüfung als von Hand geparst. Ein
    Nachtrag fehlt.
- **[08-projektrahmen.md](08-projektrahmen.md) Abschnitt 3:**
  - Im Modulbaum fehlt `simulation/kms`.
  - Die Port-Liste von M-3 nennt weder `KeyService` noch `AccountSealing`.
  - Die Fachkern-Regel nennt nur `account.AccountProfile`. `orchestrator.domain` nutzt aber auch
    `account.AuthMethodView`, und `OrchestratorArchitectureTest` lässt das zu.
- **KDocs:**
  - `BindingKey.kt:8` verweist auf das gelöschte `KeycloakToolCalls`.
  - `KeycloakSessionEvidence.kt:29` verweist auf ein nicht vorhandenes `upsert`.
  - Veraltet sind auch `AuthIntent.WEB_SELECT_METHOD` („Keycloak drives the rest natively“), die
    KDoc von `SessionEvidence` („or Keycloak proved“) und `PasswordCredentialPortImpl`.
  - `ToolJourney.kt:72` nennt `buildReadResponse` statt `readResponse`.

### AU-12 (Hinweis) Ein Nachweis in drei Formen, ein Subjekt in vier

- **Nachweis:** `MethodEvidence` wird seit ADR-59 dreimal abgebildet:
  - `MethodEvidenceRecord` als JSON,
  - `KeycloakSessionEvidence` als Spalten,
  - `MethodEvidenceRow` als String-Projektion.

  Jede Form hat eigene Umwandlungen von `factorTypes`, `axis` und `loa`.
- **Repository:** `KeycloakSessionEvidenceRepository` erbt von `JpaRepository` und bietet damit
  `save` an, obwohl seine KDoc „no read-modify-write path“ zusagt.
- **Subjekt:** `Subject`, `AuthSubject` (Wire-Format), `KcSubject` und das Spaltenpaar
  `accountId`/`invitation`.

**Vorschlag:**

- `MethodEvidenceRow` streichen.
- Das Repository von `Repository<…>` ableiten, damit die Zusage technisch gilt.
- Mit der nächsten Fassung des Umschlags `AuthData.amr` von der Map mit festem Wert
  `"orchestrator"` zur Liste machen.

## 4. Codequalität und Vereinfachungen

Die Punkte sind nach Nutzen für die Verständlichkeit geordnet. Aufwand: k = klein, m = mittel.

### Backend

`DPoP-demo-8x0p.8` für V-B1 bis V-B5, `DPoP-demo-8x0p.9` für V-B6 bis V-B9.

- **V-B1 (k) Toten Code entfernen.**
  - Für `BindingKey.keycloakOnly` gibt es keinen Nutzer mehr. Mit ihm fallen die Zweige in
    `DpopBindingKeyResolver.kt:57` und `BindingKeyOpenApiConfig.kt:51`.
  - `InvalidStateException` wird nie geworfen. Mit ihr fällt der Handler in
    `OrchestratorExceptionHandler.kt:131`.
  - Ohne Aufrufer sind auch: `SessionManagementService.updateChannelState` und
    `bindAccountAndAppTokenSession`, `KmsTransit.keyInfo` und `OrchestratorException.processAborted`.
  - Nur Tests nutzen `AcrLevels.rank/levelAt/min` und `SessionEvidence.from`.
- **V-B2 (m) Ein Einmalcode-Helfer in `tool_api`.**
  - `auth_sms/TanGenerator` und `auth_email/EmailCodeGenerator` sind bis auf die Namen gleich, und
    `auth_qr/ConfirmationCodeDigest` ist eine Teilkopie.
  - Daran hängen weitere Kopien: `SmsSendLimit` und `EmailSendLimit`, `AuthSmsLookupFlow` und
    `AuthEmailLookupFlow` sowie die Testhelfer `withPendingTan` und `withSendBudgetUsedUp`.
  - Skizze: `OneTimeCodes.issue()/matches()/digest()` und ein `IssuedCode(hash, expiresAt)` in der
    ToolSession. `tool_api` enthält mit `ratelimit/` und `kms/` schon gemeinsame Hilfen dieser Art.
- **V-B3 (k) Ein Name für „ein Nachweis je Gerät“.** `Tool.allowsMultipleInstances` und
  `Tool.boundToCallerKey` (`Tool.kt:259,262`) liefern beide `module.onePerDevice`. Sie sind an
  sieben Stellen gemischt in Gebrauch. Vorschlag: nur `onePerDevice`.
- **V-B4 (k) Enrollment-Referenz und Cleanups.**
  - Vier Handler (sms, password, device, kobil) lösen die Referenz gleich auf: Typ prüfen,
    `toLongOrNull`, `findByIdOrNull` und `UnresolvableReferenceException`, jeweils mit gleichen
    Texten.
  - Vier `*EnrollmentCleanup` sind identisch.
  - Skizze: `CrudRepository.requireEnrollment(ref, type)` und `RowEnrollmentCleanup(type, repo)`
    als `@Bean`.
- **V-B5 (k) Namen an die Tool-Ids angleichen.**
  - `approve-qr` heißt im Code `ConfirmQrLogin…`.
  - `auth-invite-lookup` heißt `AuthInvite…`.
  - `IdNectToolSession` passt nicht zum Präfix `IdentNect…`.
  - Wertklassen werden als String durchgereicht: `ToolContext.toolId` ist ein `String`, ebenso
    `methodInstanceId` (15-mal, dreimal per `UUID.fromString` geparst). ACR ist mal `AcrLevel`,
    mal `String`.
- **V-B6 (k bis m) Weniger Boilerplate in den Tool-Controllern.**
  - Die 23 Controller bestehen zu etwa 60 % aus Boilerplate.
  - Das Gegenstück zu `activated`/`readResponse` fehlt: Ein `applied(ctx, outcome)` ersetzt 23-mal
    `ResponseEntity.ok(toolJourney.applyOutcome(…))`.
  - `read()` sollte `ToolOutcome.InProgress` liefern. Dann entfällt der Laufzeit-Cast in
    `ToolJourney.kt:179`.
  - Die Beispiel-UUIDs stehen 60-mal im Code. Als `const val` reicht je eine Stelle.
  - Optional: die OpenAPI-Annotationen in Interfaces, auch für `ChannelController` (510 Zeilen,
    überwiegend Annotationen).
- **V-B7 (m) `ToolStep` statt `Pair<String, StepData>`.**
  - Jede Flow-Datei liefert `describe(): Pair`, und 19 Handler zerlegen das Paar wieder.
  - `EnrollKobilFlow` und die Device-Flows weichen im Typ ab.
  - Schrittnamen sind mal Konstante, mal Literal.
  - Skizze: `data class ToolStep(name, data)` mit `inProgress()`.
- **V-B8 (k) Nullbarkeit und Marker-Sessions.**
  - In den ToolSessions sind Felder nullbar, die `start()` immer setzt. Danach braucht es
    `checkNotNull`.
  - Für einen fehlenden Wert gibt es drei Konventionen: `null`, `""` und nicht nullbar.
  - Sechs Sessions bestehen nur aus `started = true`. Ihr Nutzen ist zu prüfen.
- **V-B9 (k) Lange Funktionen schneiden.**
  - `KeycloakChannelService.upsertChannel` (90 Zeilen) zerfällt natürlich in drei Teile:
    `restoredEvidence`, `openFreshChannel` und `bindExistingChannel`.
  - `signedOutAtKeycloak` verzweigt dreimal über `subject`.
  - `ChannelService` schreibt „neu laden und antworten“ siebenmal aus. Dafür reicht ein Helfer
    `respondAfter`.
  - Die fünf reinen Schlüssel-Delegates aus `AccountService` (532 Zeilen) gehören in eine Fassade
    `JourneyKeys`.

### Keycloak-Erweiterung

`DPoP-demo-8x0p.10`

- **V-E1 (m) Ein Treiber für beide Dispatch-Schleifen.**
  - `OrchestratorAuthenticator.java:77-242` und `OrchestratorManageMethodsRequiredAction.java:67-227`
    behandeln Auswahl, Rückfrage, Tool-Notizen, Auto-Aktivierung und `Unhandled` doppelt.
  - Skizze: Eine Schnittstelle `FlowPort` mit je einem Adapter für den Authentifizierungs-Flow und
    die Required Action. Darüber ein `ToolStepDriver` (`activate`, `postStep`, `render`).
  - Damit sind K-3 (fehlende `activationFields`/`actionFields`/`withQueryParams` in der Required
    Action) und der Dispatch-Teil von Q-6 erledigt.
  - Die Required Action ruft noch `context.failure()` (`:63,132,146,226`), also Keycloaks
    allgemeine Fehlerseite (`DPoP-demo-rdns`).
- **V-E2 (m) Generierte Vertragsmodelle durchgängig nutzen.**
  - `KcAccount.from`, `KcInvitation.from`, `MethodView.from` und der Tool-Katalog lesen per
    `json.path("…")` von Hand. Generiert gibt es `KeycloakAccountView`, `KeycloakInvitationView`,
    `ActiveMethodView` und `ToolCatalogEntry`.
  - Die Wire-Records gehören in ein Paket ohne Abhängigkeiten (`kcext.model`). Danach greift eine
    Regel `beFreeOfCycles`, was A-3 weitgehend löst.
- **V-E3 (k) `OrchestratorClient` (556 Zeilen) aufräumen.**
  - `texts()` wiederholt den signierten Austausch aus `send()`.
  - Die `channelSessionId` wird mal mit `segment()` gesichert, mal roh angehängt.
  - Die Sicht-Records (rund 170 Zeilen) gehören in eigene Dateien.
  - `sha256` steht doppelt in Signer und Verifier.
  - `OrchestratorApiException.errorCode` wird nie gelesen.
- **V-E4 (k) Toter Code.**
  - Ohne Nutzer sind `OrchestratorAuthenticator.toolForm`, der Parameter `error` der
    `WebFormRenderer`-Formulare (immer `null`), der Zweig `response == null` samt den
    „retry path“-Kommentaren und die Authenticator-Option `toolId`, die kein Realm setzt.
  - Ungenutzte Imports stehen in `OrchestratorNotes` (`MAPPER`, `ArrayNode`, …),
    `AccountTokenGrantType`, `OrchestratorStorageProvider` und anderen. Vorschlag: einmal
    aufräumen und Checkstyle `UnusedImports` einführen.
  - Dazu ein Muster: Die Renderer-Factories wiederholen `getId`, `version` und `template`. Ein
    Konstruktor in `AbstractWebToolRendererFactory` und die Helfer `demoText` und `stepFlag`
    machen jede Factory zum Einzeiler.

### Frontend und Theme

`DPoP-demo-8x0p.11`

- **V-F1 (m) Gemeinsame Code-Formulare.**
  - `TanInputForm` und `EmailCodeInputForm` unterscheiden sich nur in Texten und Ids, ebenso
    `EmailLookupForm` und `EmailCodeLookupForm`.
  - `SmsEnrollTanStep` und `ConfirmEmailCodeStep` folgen demselben Muster.
  - Skizze: `tools/shared/OneTimeCodeForm` und `EmailEntryForm`, mit `t('…')` beim Aufrufer.
- **V-F2 (m) `AppChannelApp.tsx` schneiden.** Die Datei hat 1203 Zeilen, 29 `useState` und 11
  `useEffect`.
  - Zwölf Handler folgen demselben Muster `try { setError(''); applyResponse(await …) } catch …`.
    Ein Helfer `run(label, fn)` ersetzt sie.
  - Den Zustand fasst ein Hook `useAppChannel` mit `useReducer` zusammen.
  - Die Demo-Spalte wird eine eigene Komponente.
  - Die Prüfung „Journey fertig“ steht dreimal da. Ein `isJourneyOver(next)` reicht.
  - Für `WebChannelView.tsx` (601 Zeilen) gilt dasselbe mit einem Hook `useWebSession`.
- **V-F3 (k) Fetch und Fehleranzeige vereinheitlichen.**
  - `nectApi.ts` und `personenverzeichnisApi.ts` haben dieselbe `call`-Funktion.
  - `api.ts:callPlain` verwirft den Fehlertext des Servers.
  - `err instanceof Error ? … : String(err)` steht 14-mal da, obwohl es `describeError` gibt.
  - Es gibt fünf Markup-Varianten für Fehler, alle ohne `role="alert"`. Eine `<ErrorCard>` reicht.
  - Der Admin-Teil von `api.ts` gehört in eine eigene Datei `adminApi.ts`.
- **V-F4 (k) Generierte Request-Typen für Tool-PATCHes.** `types.ts` verspricht, alles auf der
  Leitung komme aus `generated/models`. Trotzdem tippen alle `tools/*/api.ts` ihre Bodys von Hand.
  Skizze: `submitViaPatch<AuthSmsPatchRequest>(ctx, { tan })`.
- **V-F5 (k) Lint-Kommentare ohne Wirkung.**
  - Das Frontend lintet mit oxlint, und `exhaustive-deps` ist dort nicht aktiv. Die zehn
    `eslint-disable-next-line react-hooks/exhaustive-deps` wirken also nicht.
  - Das Theme hat gar keinen Linter.
  - Zu entscheiden: die Regel aktivieren oder die Kommentare entfernen.
- **V-F6 (k bis m) Kleinkram.**
  - Die Texthash-Logik (`textId`, SHA-256) ist zwischen `frontend/src/texts.ts` und
    `keycloak-theme/src/texts.ts` kopiert.
  - Die sieben `entries/*/main.tsx` sind gleich gebaut.
  - In `JourneyTraceView` stehen tote Labels (`source`, `effect`).
  - In `DebugSidebar` gibt es unübersetzte Texte.
  - Im Theme:
    - Code- und Passwortfelder mit Demo-Hinweis wiederholen sich.
    - `KcPage` wählt die Seite per `switch` statt über eine Tabelle.
    - Demo-Personen gehen als JSON-String mit `"null"` als Platzhalter an die Seite.

### Kommentare (Stichproben)

Geschichte im Kommentar ist im Hauptcode selten. Ausnahme: `keycloak-extension/build.gradle.kts:73`
(„Vorher … Jetzt …“, „BEWUSST KEINE“).

Häufiger sind zwei andere Muster:

- **Gemischte Sprache:** Deutsche Kommentare stehen in sonst englischem Code, etwa in
  `KeycloakSetupEnvironment.kt`, `KeycloakResponseSigning.kt`, `WebToolRenderContext.java:12` und
  `AppChannelApp.tsx:131`.
- **Verweise auf Kopien:** etwa „A deliberate copy of …“ in `EmailCodeGenerator`. Sie entfallen mit
  V-B2.

### Größte Dateien

| Bereich | Datei | Zeilen | Schnitt |
|---|---|---|---|
| Backend | `JourneyService.kt` | 585 | mäßig: Abbruch und Ende als `JourneyTermination` |
| Backend | `AccountService.kt` | 532 | ja, V-B9 |
| Backend | `ChannelController.kt` | 510 | nur die Annotationen, V-B6 |
| Backend | `JourneyActionExecutor.kt` | 447 | nein |
| Frontend | `AppChannelApp.tsx` | 1203 | ja, V-F2 |
| Frontend | `WebChannelView.tsx` | 601 | ja, V-F2 |
| Frontend | `api.ts` | 519 | ja, V-F3 |
| Erweiterung | `OrchestratorClient.java` | 556 | ja, V-E3 |
| Erweiterung | `OrchestratorAuthenticator.java` | 322 | wird mit V-E1 kleiner |

## 5. Korrekturen an offene-befunde.md

| Eintrag | Stand am 2026-10-09 |
|---|---|
| Abschnitt 7 „Passwortwechsel“ | Erledigt: Die Verfahrensverwaltung bietet `enroll-password` als „ersetzen“ an (`/methods/{id}/changes`, `replaces`). Offen bleiben `164n.27` und `164n.28`. |
| SA-27 | Die Bedingung für „bewusst“ ist eingetreten, siehe AU-10. |
| A-7 | Zum Teil erledigt: Statt `Subject.Invitation.hash` gibt es `id: InvitationId`. |
| S-2 | Der Aufräumjob für `nect.ident_case` widerspricht M17 in [08-projektrahmen.md](08-projektrahmen.md). Das simulierte Nect unterliegt nicht unseren Aufbewahrungsregeln. Das `retry`-Budget bleibt offen. |
| A-6 | Größer als beschrieben: Auch `OrchestratorTexts`, `OrchestratorToolCatalog` (`nanoTime`) und `OrchestratorNotes` lesen die Uhr. |
| Q-6/K-10 | Die Einordnung des nächsten Schritts ist entdoppelt (`OrchestratorNextDispatch`). Die Behandlung der Ergebnisse steht weiter doppelt da (V-E1). `9ppv.12` nennt noch den entfernten Update-Authenticator. |
| `DPoP-demo-rdns` | Zum Teil erledigt: Der Authenticator ruft kein `failure()` mehr. Die Required Action tut es noch. |
| Lesepfad Sicherheit, Federation | Nach ADR-58 falsch, siehe AU-8. |

Weiter offen und richtig beschrieben sind A-3, A-8, K-3, K-6, K-8, K-14, A-13, Q-4 (noch 17 `!!`),
Q-5, Q-7 und Q-10.

## 6. Geprüft und in Ordnung

Diese Bereiche zeigen keinen neuen Befund:

- **DPoP:**
  - Nur ES256, ES384 und ES512 mit EC-JWK und ohne privaten Schlüssel.
  - `htm` und `htu` werden normalisiert geprüft. Für `iat` gelten 30 s in die Zukunft und 60 s
    Höchstalter.
  - Die Replay-Sperre ist ein atomarer Insert unter SHA-256(Thumbprint:jti) in eigener
    Transaktion.
  - Die Bindung an den Kanal wird in konstanter Zeit verglichen.
- **Peer-Auth und Antwortsignatur:**
  - Geprüft werden `typ`, ES256, `kid` mit gedrosseltem JWKS-Nachladen, `iss`/`aud`,
    `htm`/`htu` samt Query, `body_sha256`, `channel_binding` und `jti`.
  - Die Erweiterung prüft Typ, Signatur, `req`, Status, Body-Hash und Zeiten der Antwort, auch bei
    Text-Bundles.
- **Autorisierung:**
  - Alle Kanal- und Tool-Endpunkte laufen über `@BindingKey` und `ChannelAccessGuard`.
  - Schreibende Aufrufe prüfen das aktuelle Tool und die Sperren.
  - Einen IDOR auf ToolSession, Journey oder Kanal gibt es nicht.
- **Admin und Demo:** HTTP Basic mit Drossel. Mock-Endpunkte sind `@DemoSurface`.
  `ProductionModeCheck` deckt simulierten KMS, Verschlüsselung, Admin-Hash, H2, api-docs, Pepper,
  Lookup-Secret und TLS ab.
- **Kryptografie (ADR-52 bis ADR-56):**
  - AES-256-GCM mit 96-Bit-Zufallsnonce.
  - Zweckbindung beim Einpacken und getrennte Teilschlüssel.
  - Tagesschlüssel mit Löschfrist und Hauptschlüssel-Cache mit Räumung.
  - Die Übernahme eines Journey-Schlüssels prüft das Konto.
  - Der Modus ist je Datenbank festgelegt.
- **Keycloak:**
  - Der Subjektwechsel wird abgelehnt. Ohne Subjekt gibt es keinen Erfolg.
  - Keycloak-eigene Parameter der Action-URL werden gefiltert.
  - `AccountTokenGrantType` hat eine Client-Whitelist.
  - V7 schließt Direct Grant, `admin-cli`, Account-Konsole, „Passwort vergessen“ und
    `offline_access`.
  - Der Browser-Client nutzt PKCE S256.
- **Theme und Frontend:**
  - Es gibt kein `dangerouslySetInnerHTML`.
  - Die Nect-`returnUri` ist auf den Realm-Präfix begrenzt.
  - DPoP- und Geräteschlüssel sind nicht extrahierbar.
  - Es gibt weder einen Open Redirect noch `postMessage`.
- **CI und Container:**
  - Es gibt kein `pull_request_target`, und die Actions sind per SHA gepinnt.
  - Die Workflows haben nur Leserechte, und es gibt keine `${{ }}`-Injection.
  - Beide Images laufen ohne root.
- **Fehler und Logs:** Fehlerantworten enthalten nur feste Texte. Tokens, PINs, Rufnummern und
  Schlüssel werden nicht geloggt.
- **ADR-59:** Alle vier Löschwege sind vorhanden: Abmeldung, `RetentionJob`, Widerruf und
  Kontolöschung. Einladungen legen nichts ab, und zwei gleichzeitige Tabs schreiben getrennte
  Zeilen.

## 7. Empfohlene Reihenfolge

1. **Sicherheit:** AU-2, an derselben Stelle wie das behobene AU-1. Zuerst ein fehlschlagender
   Test, dann die Prüfungen in `flowEnded` und `upsertChannel`.
2. **Die Folgen von ADR-58:** AU-9, AU-10 und AU-8.
3. **Schnelle Aufräumarbeiten:** V-B1, V-B3, V-E4, V-F5 und AU-11.
4. **V-E1 zusammen mit AU-3:** schließt K-3 und den Dispatch-Teil von Q-6.
5. **V-B2, V-B4 und V-B6:** der größte Abbau von Kopien im Backend.
6. **V-E2:** löst A-3.
7. **V-F1 bis V-F3 im Frontend.**
8. **Der Rest nach Gelegenheit.**

## 8. Stand der Behebung

| Befund | Commit-Thema | Issue |
|---|---|---|
| AU-1 | Sitzungsnachweise nur für ein Konto je Keycloak-Sitzung | `DPoP-demo-8x0p.1` |
| AU-2 | Widerrufene Verfahren kommen nicht über `flow-end` zurück | `DPoP-demo-8x0p.2` |
| AU-4 bis AU-8 | Kleine Härtungen an Sitzungsnachweisen, Schlüsseln und Federation; AU-7 nur für App-Tokens | `DPoP-demo-8x0p.4` |
