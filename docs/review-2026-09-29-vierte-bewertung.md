# Vierte Bewertung (2026-09-29): Architektur, Konzept, Codequalität, Sicherheit

Umfang: der Backend-Kern (`core/`, `contract/`, `tools/`) und die Keycloak-Erweiterung samt
Realm-Migrationen. Simulationen und Demo-Anteile nur dort, wo sie mit dem Kern zusammenarbeiten
(Ports, `@DemoSurface`, `demo.mode`). Maßstab ist [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md):
Der Kern soll produktionsreif sein, jede Sicherheitszusage gilt ohne unbenannte Annahme an die
Umgebung.

Grundlage: vier getrennte Prüfungen (Sicherheit Kern, Keycloak-Erweiterung und -Anbindung,
Architektur und Konzept, Codequalität und Tests) mit vollständigem Lesen der zentralen Klassen und
Nachschlagen des Keycloak-Verhaltens im Quelltext 26.6.4; dazu eigene Nachprüfung jedes Befunds,
der hier als „mittel“ steht. Die dritte Bewertung vom 2026-09-27 ist abgearbeitet und gelöscht;
ihre mittleren Befunde wurden hier auf Regression geprüft (Abschnitt 2), ihre niedrigen leben als
Issues unter `DPoP-demo-9ppv` weiter.

Seit der dritten Bewertung neu: die `Clock`-Bean, Nect im Web-Kanal über die Action-URL (ADR-47),
der Vorgangszugang mit Einmalkennwort (ADR-48, Modul `auth_invite`, zweite Federation), Realm-
Fristen (V5), `ident-eid` in einem Schritt, `ChannelSession` mit Ids statt Entitäten, die
Paketaufteilung der Erweiterung. Das ist der Schwerpunkt dieser Runde.

## 1. Urteil in drei Sätzen

- **Die dritte Bewertung ist vollständig umgesetzt, nichts ist zurückgefallen.** Alle 17 mittleren
  Befunde stehen an der genannten Stelle im Code und sind per Regel oder Test gesichert; die Uhr
  ist überall die `Clock`-Bean; ADR-47 und ADR-48 halten die Port- und Ereignismuster, die Doku ist
  nachgezogen, keine umgesetzte Idee liegt mehr unter `docs/ideen`. Kein Befund dieser Runde ist
  „hoch“.
- **Der wichtigste Befund ist eine leere Zusage: `loa-max-age` gilt für Orchestrator-Nachweise
  nicht.** RestoreData trägt kein Nachweisalter; der Resume-Pfad stellt einen 30 Minuten alten
  `loa2`-Nachweis in jedem neuen Durchlauf wieder her und erneuert ihn am Ende, bis zu 10 Stunden
  lang (S-1). 07-betrieb verspricht seit V5 das Gegenteil. Daneben zählt Keycloaks Brute-Force-
  Schutz im Step-up Orchestrator-Ausfälle als Fehlversuch des Nutzers (K-1), und der Nect-Retry im
  Web-Kanal kann nicht funktionieren, weil die gemerkte Rücksprungadresse einen einmaligen
  Aktionscode trägt (K-2).
- **Der Vorgangszugang ist auf der Antwortseite ein Subjekt, auf der Anfrageseite nur ein Konto.**
  Daraus folgen die Lücken der Einladung: Abmeldung erreicht den Orchestrator nie (A-1), der
  Upsert könnte einen Einladungskanal still zum Konto machen (A-2), Step-up läuft anonym (K-6),
  Erfolg setzt den Personenzähler nicht zurück (A-5). Und das neue Modul ist das einzige ohne
  eigenen Test (Q-2), seine Integrationstests umgehen die `when`/`then`-Regel (Q-1).

## 2. Regressionsprüfung der dritten Bewertung

Alle mittleren Befunde sind im Code geblieben:

- **Sicherheit.** S-1: Wertobjekte melden ohne Rohwert, `handleIllegalArgument` loggt nur den Typ
  (`OrchestratorExceptionHandler.kt:70-75`), `NoSecretsInLogIntegrationTest`. S-2: `case when
  t.lockedUntil <= :now then 1 else failedCount + 1` in einem `UPDATE`
  (`AttemptThrottleRepository.kt:38-57`), `AttemptLockoutExpiryDbTest`. S-5: feste `DpopFailure`-
  Codes, neutraler Peer-Auth-Text. S-10: Proof-Fenster 60 s.
- **Keycloak.** K-1: Schlüssel als `PASSWORD` mit `secret=true` (`OrchestratorSettings.java:62-70`),
  Maskierung mit `StripSecretsUtils` getestet. K-2: `LoginCompletion.judge` (11 Fälle) – fertig
  nur mit Subjekt, demselben Subjekt und `acr ≥ targetAcr`. K-3: Texte über
  `OrchestratorClient.texts`, auch `304` verifiziert. K-4/K-11: 3 s/10 s beidseitig, ein
  `HttpClient`, `KeycloakJwkSource` mit Größenlimit. K-6: `isValid` wirft `ModelException`, ebenso
  `InvitationStorageProvider`. K-7: V5 setzt AccessToken 300 s, SSO idle 1 800 s, max 36 000 s,
  `sslRequired=external`, `loa-max-age` 1 800 s. K-15: `AccountTokenClaims` whitelistet `acr`/`amr`.
- **Architektur.** A-1: `ToolCatalog.descriptorOf(method, role)`. A-2: Positivliste
  (`OrchestratorArchitectureTest.kt:725-734`). A-3: `MgmtPasswordController` in `auth_password`,
  Port `KeycloakToolCalls`, `@BindingKey(keycloakOnly)`. A-4: `CandidateTools.forPeerApproval`,
  `CoreNamesNoToolTest` (0 Treffer). A-5: `DemoMode`-Bean, `DemoModeSwitchTest`. A-6: Log-Appender-
  Test, Register I-19. A-15: `AccountArchitectureTest.kt:46-53`.
- **Qualität.** Q-1: `UnresolvableReferenceException` in allen sechs Auth-Handlern, je ein Test.
  Q-2: `ChannelSessionLifecycle`, `applyTransition` 18 Zeilen, `JourneyService` 596 Zeilen. Q-3:
  die sechs genannten Dateien halten die Regel. Q-4: die 19 Testklassen existieren, prüfen
  Ergebnisse statt Aufrufreihenfolgen, `relaxed` nur für Nebenrollen.

Weiter offen und unverändert (je Issue): K-5, K-8, K-9 (Fläche gewachsen, siehe K-7 unten), K-12,
A-7 bis A-14, A-16, Q-5 bis Q-17.

## 3. Befunde

Reihenfolge innerhalb jedes Abschnitts nach Schwere. Jeder Punkt nennt Fundstelle, Szenario und
Gegenmaßnahme. „Bewusst“ heißt: eine ADR oder ein Kommentar legt das so fest; der Punkt steht
hier als benanntes Restrisiko, nicht als Fehler. Entscheidungen, die den `tool_api`-Vertrag oder
das Modell ändern, sind als solche markiert und dem Inhaber vorzulegen.

### 3.1 Sicherheit im Kern

- **S-1 (mittel) RestoreData trägt kein Nachweisalter; `loa-max-age` wird über den Resume-Pfad
  unterlaufen.** `RestoreDataCodec.toClaim` (`channel/RestoreDataCodec.kt:69-78`) schreibt je
  Faktor `method`, `loa`, `enrolledUnderAcr`, `factorTypes`, `source`, `amrSourceId` – keinen
  Zeitpunkt; `EvidenceTrail.AmrRecord` hat keinen; TTL des Tokens 12 h. `orchestrator-resume`
  steht als erster Schritt im Browser-Flow vor beiden LoA-Subflows (V1 Zeilen 101-125) und
  schickt bei jedem Durchlauf `restoreData` mit dem angefragten `targetAcr`
  (`OrchestratorResumeAuthenticator.java:56-61`); `KcChannelService.upsertChannel` wendet die
  Faktoren als ersten Übergang an (`:178-182`), `KcSelectMethodStrategy.afterProof` antwortet
  `Authenticated`, sobald `isSatisfied(evidence, acrFloor, account)` (`:73-80`). Am Ende jedes
  Durchlaufs stellt `stashRestoreDataAtFlowEnd` ein neues Token mit derselben Evidenz aus
  (`OrchestratorNotes.java:152-169`). Szenario: `loa2` per `auth-sms`+`auth-password` um 9:00.
  Um 9:31 verlangt ein Client `acr_values=2`; Keycloaks Condition-LoA (V5: 1 800 s) startet den
  LoA-2-Subflow, aber der Resume hat den Kanal schon mit den alten Faktoren auf `AUTHENTICATED`
  `loa2` gebracht; `LoginCompletion` sieht Subjekt, Konto und `acr ≥ loa2` und meldet `Complete`.
  Keycloak stuft die Sitzung erneut auf `loa2`, ohne Nachweis, und das wiederholt sich bis
  `ssoSessionMax` (10 h). 07-betrieb §3 sagt seit V5: „danach … verlangt eine Anfrage mit
  `acr_values=2` einen frischen Nachweis“ – das gilt nur für native Keycloak-Faktoren. Kein
  Bypass ohne Zugriff auf den Browser mit laufender SSO-Sitzung, aber genau davor soll
  `loa-max-age` schützen; die Fristenmigration ist für Orchestrator-Nachweise wirkungslos. Fix:
  `provenAt` je `MethodEvidence`/`AmrRecord` (gesetzt in `JourneyRecorder.recordToolCompletion`
  und für native Faktoren in `KcChannelService`), im Claim mitgeführt; beim Restore fallen
  Faktoren, deren Alter `kc.restore.max-factor-age` (= `loa-max-age`) übersteigt, weg –
  oder `AuthPolicy.resolveAcr` bewertet Frische, dann gilt es auch für App-Sitzungen (Entscheidung
  des Inhabers). Test mit gestellter Uhr: RestoreData bei T, Upsert `targetAcr=loa2` bei T+10 min
  → `authenticated`, bei T+31 min → `selectMethod`. Register: I-23 ergänzen oder eigene Invariante
  „ein Nachweis altert“. Gehört zu `DPoP-demo-oe06`.
- **S-2 (niedrig) `ident-nect`: `retry` ohne Budget, Nect-Fälle ohne Aufbewahrung.**
  `IdentNectToolHandler.patch` (`:72-79`) legt je `retry=true` einen neuen Fall an, kein Zähler,
  kein Fehlversuch; `nect.ident_case` hat keinen Sweeper (kein Treffer im Modul). Ein Kanal
  (DPoP-Schlüssel kostenlos) hält die ToolSession 10 Minuten und schickt `retry` in Schleife: eine
  dauerhafte Zeile je Aufruf; mit echtem Nect (`DPoP-demo-v033`) ein bezahlter Auftrag je Aufruf.
  Fix: `AttemptBudget` des Moduls (ADR-44, wie die Versandbudgets, etwa 3 je ToolSession/10 min →
  429); Sweeper für `ident_case` nach `createdAt`. Test: vierter `retry` → 429.
- **S-3 (niedrig) Nutzereingaben in `OrchestratorException.detail` erreichen das Log ungefiltert.**
  `ChannelService.kt:93` (`intent=` aus dem Query-Parameter), `ToolJourneyService.kt:118-126`
  (`toolId=` aus dem Pfad), `ToolHandlerRegistry.kt:54`, `KcChannelService.kt:117`
  (`nativeToolId` aus dem Keycloak-Body); der Handler loggt `ex.message` samt Detail
  (`OrchestratorExceptionHandler.kt:60`). Werte sind unbegrenzt und dürfen Zeilenumbrüche tragen:
  `POST /app/channels?intent=foo%0A2026-09-29 INFO …` fälscht eine Logzeile (im ECS-JSON-Profil
  entschärft, lokal nicht). I-19 ist nur für Wertobjekte gesichert, nicht für Pfad- und
  Query-Werte. Fix: Detail erst nach der Auflösung bilden (Enum-Name, Descriptor), sonst `take(40)`
  und Steuerzeichen ersetzen in `OrchestratorException.detailOf(...)`;
  `NoSecretsInLogIntegrationTest` um eine 400/404-Anfrage mit `%0A` erweitern.
- **S-4 (Hinweis) `ServerInfoController` ist weder `@DemoSurface` noch hinter dem Admin-Login.**
  `admin/ServerInfoController.kt:77-91`: `/orchestrator/demo/server-info`, ohne Anmeldung, auch
  bei `demo.mode=false` („read-only and without login“). Liefert Health je Komponente, alle
  `identity.*`-Zähler (Drosseln je Scope, Retention-Mengen) und Latenzen je Zielhost – dieselben
  Werte, für die das Management-Port bewusst nicht geroutet ist (`application.yml:273-292`).
  Fix: `@DemoSurface`, oder außerhalb des Demomodus nur `keycloak`/`demoMode` ohne `operations`.
- **S-5 (Hinweis) `availableTools` wird ungeprüft persistiert.** `ChannelService.kt:89`,
  `KcChannelService.kt:86,152` → JSON in `channel_session.available_tools`; weder Anzahl noch
  Länge begrenzt; 20 Kanäle je 5 min je Schlüssel, Schlüssel kostenlos. Fix: gegen den Katalog
  schneiden (`toolRegistry.descriptors()`), Unbekanntes verwerfen – sicherheitsneutral, ein
  unbekannter Eintrag wird nie angeboten.
- **S-6 (Hinweis) `targetAcr` im kc-Upsert wird nicht validiert.** `KcChannelService.kt:173` →
  `AcrLevels.max` bildet Unbekanntes auf `none` ab: ein Tippfehler in der Erweiterung hebt den
  Floor still nicht an, statt laut zu scheitern; der App-Pfad prüft mit `AcrLevel.requested`
  (400). Fix: dasselbe hier.
- **S-7 (Hinweis, bewusst) `auth-invite`: unbekannte Nummer kostet nichts, bekannte antwortet
  messbar anders.** `AuthInviteToolHandler.kt:46-48` ruft `redeem` nur bei `personId != null &&
  !throttled`; `Einladungen.kt:107-114` hasht je offener Einladung. Unbekannte Nummer →
  `attempted = null`, nichts zählt außer dem Journey-Budget; kc-Upserts unterliegen keiner
  `ChannelCreationThrottle`. Ergebnis: KVNR-Existenz per Zeitmessung, unbegrenzt viele Nummern je
  Browser. Das Kennwort selbst (5 Versuche/15 min je Person) ist nicht ratbar. Gehört zu
  `DPoP-demo-36xz`; der Freischaltcode hat dieselbe Eigenschaft.
- **S-8 (Hinweis, bewusst) Keycloaks Action-URL samt Aktionscode liegt in zwei Tabellen und geht
  an das Fremdsystem.** `ident_nect.ident_tool_session.return_uri` (2 048 Zeichen, 24 h),
  `nect.ident_case.callback_uri` (unbegrenzt, nie geräumt, S-2). Präfix-Prüfung gegen
  `…/realms/` mit Schrägstrich, kein Host-Suffix-Trick. Der Code ist ohne `AUTH_SESSION_ID`-Cookie
  wertlos und einmalig (K-2). ADR-47 nimmt das in Kauf. Option: eigene Rücksprungadresse
  `/orchestrator/nect-return/{toolSessionId}`, der Orchestrator leitet selbst weiter; dann verlässt
  der Code den Kern nicht.
- **S-9 (Hinweis) Abhängigkeiten.** Spring Boot 4.1.0, Kotlin 2.4.0, Modulith 2.1.1, nimbus 10.10,
  BouncyCastle 1.81, springdoc 3.1.1 aktuell. Jackson 2.19.0 nur in der Erweiterung, Nachfolger
  vorhanden. Gegen keinen CVE-Feed geprüft (Abschnitt 8).

### 3.2 Keycloak-Erweiterung und -Anbindung

- **K-1 (mittel) Keycloaks Brute-Force-Schutz zählt im Step-up Orchestrator-Fehler als
  Fehlversuch – echte Fehlversuche nicht.** `OrchestratorAuthenticator.action:121-123` antwortet
  auf jede `OrchestratorApiException` (409, 422, 500, 503) mit
  `failureChallenge(INVALID_CREDENTIALS)`. Keycloak (`DefaultAuthenticationFlow:533-537` →
  `AuthenticationManager.lookupUserForBruteForceLog:1755-1757`) bucht das dem
  `getAuthenticatedUser()` – im Step-up immer gesetzt, in der Erstanmeldung ab
  `handleResponse:133-144`. Ein echter Fehlversuch kommt dagegen als `200` mit `stepData.error`
  und zählt in Keycloak nicht. Szenario: fünf 503 in 15 Minuten, während ein Nutzer ein Verfahren
  hinzufügt → Keycloak sperrt ihn. Verfügbarkeit, kein Bypass; `authenticate()` (`:68-73`)
  macht es richtig. Fix: nie `failureChallenge` aus Transportfehlern; 4xx →
  `context.challenge(errorForm)`, 5xx → `failure(INTERNAL_ERROR)`. Kein zweiter Zähler neben
  ADR-44. Test mit gemocktem `AuthenticationFlowContext`: bei 503 kein `failureChallenge`; setzt
  K-12 (alt) voraus.
- **K-2 (mittel) Der Nect-Retry im Web-Kanal führt auf eine verbrauchte Rücksprungadresse.**
  `IdentNectRendererFactory.activationFields:48-50` gibt `getActionUrl(generateAccessCode())`
  mit; der Orchestrator merkt sie am Fall und nutzt sie beim `retry` wieder
  (`IdentNectToolHandler.kt:74-79`; ADR-47 Zeile 11: „damit ein `retry` sie behält“). Keycloaks
  Aktionscode ist einmalig: `CodeGenerateUtil.verifyCode` entfernt `active_code` bei jeder
  Prüfung, `retrieveCode` erzeugt beim nächsten Rendern einen neuen. Ablauf: Aktivierung mit
  Code A → Nect kehrt mit A zurück (verbraucht), Fall scheitert, Seite mit Code B → „Erneut
  versuchen“ postet B (verbraucht), neuer Fall mit alter Adresse A → Nect schickt auf A →
  `SessionCodeChecks` leitet ohne `nectCaseId` mit `EXPIRED_ACTION` auf die letzte Execution-URL →
  wieder „Weiter zu Nect“; der zweite Fall wird nie eingelöst. Kein Sicherheitsproblem, aber ein
  per ADR versprochener Ablauf, der nicht kann; `IdentNectIntegrationTest` prüft nur gegen den
  Orchestrator, `DPoP-demo-z90h` nur den Erfolgsfall. Fix: der Web-Kanal gibt beim Retry eine
  frische Adresse mit (`IdentNectPatchRequest.returnUri`, Präfix-Check wie beim Start; in
  `dispatchToolAction` ein Factory-Hook analog `activationFields`); App-Kanal unverändert;
  ADR-47 berichtigen. Tests: Retry-PATCH trägt `returnUri`, `IdentNectToolHandlerTest` „Retry
  ersetzt Adresse“, `DPoP-demo-z90h` um Retry erweitern.
- **K-3 (niedrig) Die Verfahrensverwaltung aktiviert Tools ohne `activationFields`.**
  `OrchestratorManageMethodsRequiredAction:110,194` ruft `activateTool(channelSessionId, toolId)`
  ohne Renderer-Felder; nur `OrchestratorAuthenticator.activateTool:254-258` fragt sie ab.
  `reIdentCandidates` (`DefaultAuthPolicy:168-172`) schließt `ident-nect` ein → Rücksprung auf
  `/app/`, der Nutzer landet in der App, die Keycloak-Sitzung hängt. Wurzel ist K-12 (alt, zwei
  Dispatcher). Fix: gemeinsame Aktivierung mit Renderer-Feldern; Test für den Verwaltungspfad.
- **K-4 (niedrig) Nutzereingaben ungeprüft in Pfadsegmenten der signierten Anfrage.** `toolId`
  (Auswahlformular, `action:95-100`, ManageMethods `:105-110`) und `removeMethodInstanceId`
  (`:85-88`) landen roh in `"/tools/" + toolId` bzw. `"/methods/" + id`
  (`OrchestratorClient:144,155`); Keycloak signiert die so gebildete URL als `htu`. Werte mit `?`,
  `/`, `..` verschieben den Endpunkt; Anker und Orchestrator-Autorisierung halten es beim eigenen
  Kanal, aber es ist die einzige Stelle, an der Formulardaten die Adresse einer signierten Anfrage
  bestimmen. Fix: `toolId` gegen die angebotenen Optionen, `methodInstanceId` als UUID, Segmente
  kodieren. Test in `OrchestratorNextDispatchTest`.
- **K-5 (niedrig) Step-up auf einer Einladungssitzung läuft anonym.** `authenticate:52` liest nur
  `orchestratorAccountId`, für `InvitationUser` bewusst leer; `KcChannelUpsertRequest` kennt kein
  Subjekt. Der Kanal hat kein Subjekt, alle `LOOKUP_AUTH`-Kandidaten werden angeboten, auch
  Konto-Anmeldungen, die `LoginCompletion:47` am Ende zu Recht verwirft – mit Keycloaks
  Fehlerseite statt einer Erklärung. ADR-48 lässt offen, ob eine Einladung aufwertbar ist.
  Gemeinsame Wurzel mit A-1/A-2: `subject` im Upsert (Entscheidung des Inhabers).
- **K-6 (niedrig) Antwort-JWKS mit Nimbus-Voreinstellungen.** `OrchestratorResponseVerifier:53-61`
  `JWKSourceBuilder.create(url).retrying(true)`: Default 500 ms Connect/Read, 5 min Cache, kein
  `outageTolerant`. Ein langsames JWKS beim Cache-Ablauf → jede Antwort abgelehnt bis zum
  nächsten Versuch (K-4 alt hat die Anfrageseite gedeckt, nicht diese). Fix:
  `DefaultResourceRetriever(3000, 10000, 65536)`, `outageTolerant`. Nicht gemessen.
- **K-7 (niedrig) JSON in `<script>` – Fläche gewachsen (K-9 alt, `DPoP-demo-9ppv.11`).**
  `demo-person-picker.ftl:16-17` (`?no_esc`) trägt jetzt auch `demoInvitationsJson`
  (`AuthInviteRendererFactory:40`). Weiter nur Seed-Daten im Demomodus.
- **K-8 (Hinweis, bewusst) Rücksprung für jedes Tool offen.** `withQueryParams:60-69` macht die
  Query jedes GET auf die Action-URL zur Tool-Eingabe, inklusive `orchestrator_back`/
  `orchestrator_abandon`. Braucht Code und Cookie, also den Browser; nur mehr Fläche als nötig.
  Option: nur für Tools mit `activationFields`.
- **K-9 (Hinweis) Paketaufteilung mit Zyklen.** Siehe A-3.
- **K-10 (Hinweis) Testlücken (K-12 alt, `DPoP-demo-9ppv.12`) – die Liste wächst.** Ohne Test:
  beide Dispatcher (K-1 und K-3 hätte ein Test gefunden), Resume-/Update-Authenticator,
  `AccountTokenGrantType.process`, `WebFormRenderer` (178 Zeilen), `AccountRemoval`,
  `MigrationClientBootstrapFactory.ensureClient`, 11 von 14 Renderer-Factories. Neu mit Test:
  `OrchestratorNextDispatch.classify`, `LoginCompletion.judge`, `AccountTokenClaims`.

### 3.3 Architektur und Konzept

- **A-1 (mittel) Die Abmeldung eines Einladungs-Nutzers erreicht den Orchestrator nie; 07-betrieb
  sagt das Gegenteil zu.** `SignInLogEventListener.accountToReport` (`event/`, Zeilen 76-88)
  meldet nur `LOGOUT`-Ereignisse der Konto-Federation mit numerischer externer Id;
  `OrchestratorClient.reportSignOut(long accountId, …)`, `KcSignOutController`
  (`POST /kc/accounts/{accountId}/sign-outs`) und `KcChannelService.signedOutAtKeycloak`
  (`:63`, `findByAccountId`) kennen nur Konten. Ein Einladungs-Nutzer (`f:<UUID>:<Hash>`) fällt an
  der ersten Stelle heraus; `InvitationEnded` beendet nur die Keycloak-Sitzung
  (`KeycloakInvitationLogoutListener`), nicht den Kanal. 07-betrieb Zeilen 218-222: „Keycloak
  meldet sie dem Orchestrator …, und das beendet die noch laufenden Kanäle dieser Sitzung sofort,
  Web- wie App-Kanal.“ Für einen Einladungskanal gilt nur die per `restoreData` gekappte Frist:
  nach der Abmeldung bleibt er bis dahin `AUTHENTICATED`; `ActiveSessions` zählt ihn als lebend.
  Dazu kennt das Anmeldeprotokoll den Vorgangszugang nicht (`JourneyRecorder.recordSignIn`/
  `recordSignOut` kehren ohne `accountId` zurück; ADR-39/48 sagen nichts). Fix: `reportSignOut`
  mit `KcSubject` statt `long` (etwa `/kc/invitations/{id}/sign-outs`), `accountToReport` →
  `subjectToReport`; `signedOutAtKeycloak(subject, kcSessionId)` mit `findByInvitation`.
  Entscheidung des Inhabers: gehört ein Vorgangszugang ins Anmeldeprotokoll (dann `SignInLog` mit
  Subjekt), sonst ADR-48-Nachtrag „bewusst nicht“. Tests: `AuthInviteIntegrationTest` „Keycloak
  meldet die Abmeldung → Kanal `LOGGED_OUT`“, `SignInLogEventListenerTest` mit Einladungs-Id.
  Gehört zu `DPoP-demo-oe06`.
- **A-2 (mittel) Der Upsert von Keycloak nennt nur `accountId`; ein Einladungskanal würde still
  zum Konto.** `KcChannelService.upsertChannel:161-170`: der Konflikttest vergleicht
  `channel.accountId` mit `effectiveAccountId`; für `subject = Invitation` ist `accountId == null`,
  Zeile 167 setzt `subject = Account(...)`, und der Setter (`ChannelSession.kt:74-79`) löscht die
  Einladung. `authEvidenceId` zeigt weiter auf die Einladungs-Evidenz (`amr=invite`, bis `loa2`);
  `authDataFor` meldet dann `accountId` mit dem `acr` der Einladung, `restoreData` (`:219` prüft
  `invitation`, jetzt `null`) gäbe sie als Konto-Evidenz weiter. Der Executor schützt die
  Gegenrichtung (`acceptInvitation:305-316`), die Fassade nicht. Heute nicht erreichbar: `KcSubject.of`
  liefert für Einladungs-Nutzer kein `accountId`, und nur Keycloak hat den Peer-Auth-Schlüssel –
  aber genau die unbenannte Annahme an den Aufrufer, die ADR-35 verbietet; I-5 und I-29 stützen
  sich auf Tests, die den Fall nicht kennen. Fix: `if (effectiveAccountId != null &&
  channel.invitation != null) invalidState(...)` spiegelbildlich zum Konto-Konflikt; die Anfrage
  trägt `subject` wie die Antwort, `accountId` „nur zur Kompatibilität“ (Vertragsänderung,
  Entscheidung des Inhabers; löst zugleich K-5). Test in `KcChannelIntegrationTest`: Upsert mit
  `accountId` auf einem per `auth-invite` angemeldeten Kanal → 409, `invitation` bleibt.
  Register: I-29 um diesen Test ergänzen.
- **A-3 (niedrig) Die Paketaufteilung der Erweiterung (`a19ea85`) hat vier Zyklen und keine
  Prüfregel.** `client ↔ federation` (`OrchestratorClient` importiert `KcAccount`/`KcInvitation`/
  `KcSubject`, `OrchestratorSettings` → `OrchestratorStorageProviderFactory`; zurück beide
  Storage-Provider), `federation ↔ login` (`KcSubject` → `OrchestratorNotes`; `LoginCompletion`,
  `OrchestratorNotes` → `KcSubject`, `AccountUsers`), `login ↔ resource` (`WebFormRenderer` ↔
  `QrWaitStatusResourceProvider.statusUrl`), `login ↔ token` (`OrchestratorNotes.java:5`
  importiert den Mapper nur für ein `{@link}`). Toter Import `OrchestratorAcrAmrMapper.java:3`.
  Das Backend sichert Zyklenfreiheit per Slices-Regel, die Erweiterung hat kein ArchUnit.
  Fix: Wire-Records (`KcAccount`, `KcInvitation`, `KcSubject`) in ein Blatt-Paket;
  `accountId(user)` nach `AccountUsers`; `acrRank`/`isKnownAcr` in eine Blatt-Klasse; `statusUrl`
  vom Aufrufer über `WebToolRenderContext`; `slices().matching("..kcext.(*)..").should()
  .beFreeOfCycles()` als Test der Erweiterung.
- **A-4 (niedrig) „`auth-invite` nur im Web-Kanal“ ist eine Voreinstellung, keine Eigenschaft
  des Tools.** Erste Linie: `application.yml:111` (`disabled` für `APP`), vom Betreiber zur
  Laufzeit umschaltbar (ADR-32). Zweite: `LookupLoginStrategy.completed:92-96` bricht mit Text ab
  (getestet). Dritte: `JourneyActionExecutor.acceptInvitation:307` `check(channel == KEYCLOAK)` –
  500 statt 409. 03 §1 („in der Voreinstellung gesperrt“) beschreibt den Code richtig; der
  Descriptor kann es nicht sagen, weil `ChannelType` in `orchestrator.domain` liegt. Entscheidung
  des Inhabers: (a) `ChannelType` nach `tool_api`, `ToolDescriptor.channels` als unumschaltbare
  Ebene wie `demoOnly`; oder (b) bei Strategie und Voreinstellung bleiben, 03 §1 um den
  Mechanismus ergänzen, dritte Linie als `invalidState` (409).
- **A-5 (niedrig) Ein erfolgreicher Vorgangszugang setzt den Personenzähler nicht zurück.**
  `ToolJourneyService.kt:248` bucht `Attempted.Person` auf die Person; `:264` setzt bei
  `Completed.Authenticated` nur `Subject.Account` bzw. das Kanalkonto zurück, `ident-fsc` dagegen
  die Person (`:265`). Folge: vier Fehlversuche, ein Erfolg, ein Fehlversuch = 15 Minuten Sperre
  der Person, auch für `ident-fsc` (derselbe Zähler). ADR-44, 07-betrieb §4 („Erfolg setzt
  zurück“). Fix: das Ergebnis muss die Person nennen können – `Subject.Invitation(hash, personId)`
  oder ein Feld am `Authenticated` (Vertragsänderung, Entscheidung des Inhabers). Test in
  `AccountThrottleIntegrationTest`.
- **A-6 (Hinweis) Die Erweiterung liest die Uhr an vier Stellen selbst**
  (`PeerAuthAssertionSigner.java:34`, `OrchestratorSettings.java:127`,
  `OrchestratorNotes.java:159`, `OrchestratorResponseVerifier.java:111`). `ClockArchitectureTest`
  gilt laut 08 §3 nur für „die Anwendung“; die Zeitregeln der Peer-Auth sind dort nur mit echten
  Wartezeiten testbar, `OrchestratorResponseVerifierTest` prüft die Ablaufgrenze deshalb nicht.
  Fix: `Clock` als Konstruktorparameter (Default `systemUTC()`), Tests mit `Clock.fixed`; Satz in
  08 oder dieselbe Regel im Erweiterungsprojekt (mit A-3).
- **A-7 (Hinweis) Drei Formen für „wer“ in `tool_api`.** `Subject.Account(id: Long)`/
  `Subject.Invitation(hash)`, `Attempted.Account(id: Long)`/`Attempted.Person(id: String)`, im
  Umschlag `AuthSubject(AuthSubjectType, String)`. Fachlich verschieden (bewiesen, versucht,
  gemeldet) und in 03 §2 erklärt; `Subject.Invitation.hash` neben „Id der Einladung“ (ADR-48,
  05-api) stolpert. Fix: ein Name (`id`), Querverweis.
- **A-8 (Hinweis) Die Peer-Auth-Prüfung ist dreimal kopiert** (`KcAccountLookupController.
  validatePeerAuth`, `KcInvitationLookupController.byInvitation`, `KcSignOutController.signedOut`:
  dieselben sechs Zeilen Header-Parsing und Anker-Vergleich). Fix:
  `PeerAuthValidator.validateRequest(authorization, request, expectedAnchor)`.

### 3.4 Codequalität und Tests

- **Q-1 (mittel) Fünf Integrationstests setzen die Handlung ins `Then`, mit leerem `When`.**
  `AuthInviteIntegrationTest.kt:90-110`: `Given(…) { When(…) { Then(…) { val issued = issue(…);
  val (…) = openInviteTool("loa1"); kcCall(PATCH, …) … } } }` – alles im `Then`, das `When` ist
  ein leerer Container. Gleiches Muster in `KcInvitationLookupIntegrationTest`,
  `MgmtPasswordIntegrationTest`, `KcChannelIntegrationTest`, `PeerAuthRoundTripTest` (die fünf
  Dateien mit `Given`/`When`/`Then` in Großschreibung; die übrigen 49 Spring-Specs halten die
  Regel). AGENTS.md: „Die Handlung steht im `when` und läuft dort einmal; ein `then` prüft nur.“
  Q-3 der dritten Bewertung ist damit umgangen, nicht verallgemeinert. Fix: Handlung ins `When`,
  Ergebnis als `val`; ein Lint-Test in `architecture/`, der die Großschreib-Variante verbietet.
- **Q-2 (mittel) `auth_invite` ohne Handler- und Flow-Test; die einzige Absicherung ist ein
  Spring-Integrationstest.** `src/test/…/tools/auth_invite/` existiert nicht (alle zehn anderen
  Tool-Module haben 1–7 Testklassen). `AuthInviteToolHandler.patch` (`:40-59`) trägt die
  Sicherheitsentscheidung „Code allein öffnet nichts; gedrosselt, unbekannt und falsch sehen
  gleich aus“ – die Fälle, die `AuthSmsLookupToolHandlerTest` für das Schwestermodul einzeln
  durchspielt. `AuthInviteFlow.decide` (`:23-29`) ist reine Logik ohne Test.
  `AuthInviteIntegrationTest` (9 Fälle) deckt weder `throttled=true` mit gültigem Code noch
  `personId=null` mit gültigem Code. Fix: `AuthInviteToolHandlerTest`, `AuthInviteFlowTest` nach
  `IdentFscFlowTest`/`AuthSmsLookupToolHandlerTest`; Regel „jedes Tool-Modul hat einen Test“ als
  ArchUnit- oder Verzeichnistest.
- **Q-3 (niedrig) Sechs statt einer Spring-Kontext-Konfiguration.** 56 Spring-Specs; die
  `@MockkBean`-Mengen bilden fünf Cache-Schlüssel (`DpopValidator` allein 1, `+JwkThumbprintService`
  26, `+PeerAuthValidator` 6, `+TokenProvider` 1, `PeerAuthValidator` 2) plus die Specs ohne
  `IntegrationTestSupport`; springmockk legt die Mock-Definitionen in den Kontext-Schlüssel, jede
  Menge ist ein voller Start mit Flyway, JPA, Tomcat. Die dritte Bewertung hielt „eine
  Konfiguration“ fest – das war schon damals ungenau. Fix: `JwkThumbprintService` und
  `PeerAuthValidator` in `IntegrationTestSupport`, `TokenProvider` als `@Primary`-Testbean;
  Nachweis `./gradlew test --info | grep Refreshing`.
- **Q-4 (niedrig) Lookup-Flows tragen `!!` auf dem geprüften Feld; `auth_invite` kopiert das.**
  `AuthInviteFlow.kt:28` `Check(input.code!!)`, `AuthPasswordLookupFlow.kt:28` – direkt nach
  `isNullOrBlank()`, der Smart-Cast greift nicht, weil das Ergebnis in `missing` gesammelt wird.
  21 `!!` insgesamt unverändert (Q-8 alt). Fix: lokale `val` je Feld.
- **Q-5 (niedrig) Testhelfer weiter mehrfach definiert; das neue Modul fügt eine Kopie hinzu.**
  `stubAssertion` 5×, `kcHeaders` 3×, `kcPost`/`kcCall` 3×, `signDeviceProof` 3×, `enrollDevice`
  3×; `AuthInviteIntegrationTest.kt:45-56` definiert drei davon erneut. `IntegrationTestSupport`
  424 Zeilen/33 Funktionen, 45 Testklassen flach in `core/orchestrator`. Q-13 alt
  (`DPoP-demo-9ppv.32`) wächst.
- **Q-6 (niedrig) Die Dispatch-Duplikate der Erweiterung sind nur halb gehoben.**
  `OrchestratorAuthenticator.handleResponse` (`:129-227`, 98 Zeilen) und
  `OrchestratorManageMethodsRequiredAction.handleResponse` (`:156-222`) wiederholen
  Select/Tool/Confirm samt Auto-Aktivierung und Notes (rund 45 Zeilen); K-3 ist die Folge. Mit
  K-10 zusammen `DPoP-demo-9ppv.12`.
- **Q-7 (Hinweis) `ident_eid` ist vom Kover-Tor ausgenommen, trägt aber seit 09-28 Kernlogik.**
  `build.gradle.kts:49-50` schließt `tools.ident_eid.*` aus („Mock eID“); seit dem Ein-Schritt-
  Umbau hat das Modul `IdentEidFlow` mit gestaffelten `missingFields` und eigenen Tests; die
  Simulation liegt in `simulation/`. Fix: Ausschluss auf `simulation.*` beschränken.
- **Q-8 (Hinweis) Umlaute weiter gemischt (Q-11 alt):** 79 `Text("…")` mit `ae/oe/ue`, 112 mit
  echten Umlauten; `auth_invite` schreibt „ungueltig“, `ident_nect` „gehört“.
- **Q-9 (Hinweis) `e2e-keycloak` (Vorgangszugang, Login-Themes) läuft nirgends automatisch.**
  `playwright.keycloak.config.ts` sagt selbst „Not part of CI“; `ci.yml` kennt nur `test:e2e`.
  K-2 wäre dort aufgefallen. In 13-ausfuehren als Lücke benennen; ein nightly-Job mit
  `podman compose` wäre der Weg.
- **Q-10 (Hinweis) `RegisterStrategy.transition` ist mit 71 Zeilen die einzige Kotlin-Funktion
  über 60** – ein `when` über acht Zustände, rein; kein Handlungsbedarf.

## 4. Reihenfolge

Grundsatz wie bisher: zuerst, was ein Sicherheitsexperte in der ersten Stunde findet; dann die
Prüfregeln; dann Verhalten; dann Aufräumen. Vorab drei Entscheidungen des Inhabers, weil sie den
`tool_api`-Vertrag oder das Modell berühren: `subject` im kc-Upsert (A-2, K-5, A-1),
Nachweisalter im Modell oder nur im Restore (S-1), Person am `Authenticated`-Ergebnis (A-5).

**Phase Q – Zusagen einlösen**

1. S-1 Nachweisalter in RestoreData, Test mit gestellter Uhr; 07-betrieb §3 und I-23 nachziehen.
2. A-2 Einladungskanal im Upsert schützen (409); A-1 Abmeldung mit Subjekt; K-5 folgt daraus.
3. K-1 Authenticator: Transportfehler nie als Fehlversuch. K-2 Nect-Retry mit frischer Adresse,
   ADR-47 berichtigen, `DPoP-demo-z90h` um Retry erweitern.
4. A-5 Personenzähler bei erfolgreichem Vorgangszugang.

**Phase R – Prüfregeln dichten**

5. Q-2 `AuthInviteToolHandlerTest`/`AuthInviteFlowTest`; Regel „jedes Tool-Modul hat einen Test“.
6. Q-1 die fünf Tests auf `when`/`then`; Lint gegen die Großschreib-Variante.
7. A-3 Zyklen der Erweiterung lösen, ArchUnit im Erweiterungsbuild; A-6 `Clock` in der Erweiterung.
8. A-4 Kanalbindung eines Tools (Entscheidung), dritte Linie als 409.

**Phase S – Verhalten und Betrieb**

9. S-2 Retry-Budget und Sweeper für Nect; K-3 Aktivierung mit Renderer-Feldern; K-4 Pfadsegmente.
10. S-3 Log-Details ohne Rohwerte; S-4 `ServerInfoController` als Demo-Fläche; S-5
    `availableTools` schneiden; S-6 `targetAcr` validieren; K-6 JWKS-Retriever.
11. Q-3 Kontext-Konfigurationen; Q-9 `e2e-keycloak` in einen nightly-Job.

**Phase T – Aufräumen**

12. Q-4/Q-5/Q-6/Q-7/Q-8, A-7/A-8, K-7, S-8 (Option eigene Rücksprungadresse), S-9 Jackson.

Nicht in dieser Reihenfolge, weil schon offen: `DPoP-demo-9ppv.*` (niedrige Befunde der dritten
Bewertung), `DPoP-demo-oe06` (I-23), `DPoP-demo-36xz` (Lookup-Orakel), `DPoP-demo-z90h`/`v033`
(Nect), `DPoP-demo-61kp`/`bo1w` (Schlüssel, Verschlüsselung), `DPoP-demo-ai4x`/`dm2j`/`9msv`/`x25a`
(Umgebung).

## 5. Geprüft und in Ordnung

- **ADR-47 (Nect, Action-URL).** `ident_nect` kennt keinen Keycloak-Typ; die Rücksprungadresse
  ist Tool-Eingabe, wird gegen `ident-nect.return-uri-prefixes` geprüft (Default leer =
  fail-closed, Meldung ohne den Wert), nur Adressen unter `${publicKeycloakBaseUrl}/realms/`;
  kein Open Redirect (simulierter Nect leitet nur auf die gespeicherte Adresse oder `/app/`).
  Der Fall ist an die ToolSession gebunden, wird serverseitig genau einmal eingelöst;
  Dokumentnummer wird weder angefragt noch gespeichert. GET auf die Action-URL mit Code läuft in
  `action()` – am Keycloak-Quelltext bestätigt. Ein Port für beide Kanäle.
- **ADR-48 (Vorgangszugang), Orchestrator-Seite.** Port-Muster gehalten (`Invitations.redeem/find`,
  Implementierung nur im Personenverzeichnis; `auth_invite` mit `allowedDependencies = [tool_api,
  texts]`); der Orchestrator hält keine Einladungsdaten, nur den Hash als Subjekt (V33, CHECKs
  `ck_channel_session_one_subject`, `ck_auth_evidence_one_subject`); `InvitationEnded` einmal je
  Ende, Listener über die Registry. Einladung nur auf `KEYCLOAK`, nur `LOOKUP_AUTH`, nur auf
  leerem Kanal ohne Evidenz; App-Kanal bricht per Strategie ab; Einladung unter Floor wird vor
  jeder Bindung verworfen; Kontofunktionen verlangen `accountId`; `restoreData` gibt für
  Einladungskanäle nichts heraus (I-30); Kennwort nie gespeichert, Id = SHA-256 über Person,
  normalisiertes Kennwort, Vorgang, `MessageDigest.isEqual`; unbekannt/gesperrt/falsch antworten
  gleich; Kandidaten aus der Rolle, kein Tool-Name im Kern. Doku nachgezogen (01–08, Journeys,
  I-29 bis I-31, Port-Vertrag, ADR-Index), Ideen-Datei gelöscht.
- **ADR-48, Keycloak-Seite.** Zwei Federationen mit festen UUIDs, eigene `sub`-Räume; Einladung
  nie per Name/Adresse, kein Credential; `orchestratorAccountId` erzwungen leer; Suche höchstens
  ein Treffer, `*` nichts; Ausfall = `ModelException`; `LoginCompletion` lässt kein Subjekt die
  Sitzung des anderen fortsetzen; Einladungen erreichen den Grant nicht („nur Web“ hält
  technisch); `KcInvitationViews` liefert `enabled=open`, sodass nach Abschluss kein Refresh mehr
  kommt.
- **Clock-Bean.** `ClockConfig` als einzige Systemuhr, `ClockArchitectureTest` mit Selbsttest; kein
  `Instant.now()`/`LocalDate.now()`/`currentTimeMillis` im Kotlin-Main; Entitäten nehmen `now` als
  Parameter; 48 Unit-Tests mit `TEST_CLOCK`.
- **DPoP, Peer-Auth, Kanalbindung, Autorisierung in Journeys, Drosseln, Sitzungen (ADR-43),
  Konfiguration/Start, Persistenz, Fehlermodell** – unverändert zur dritten Bewertung, erneut
  gelesen: `typ` fest, nur EC, Replay per INSERT in eigener Transaktion, `htu` exakt; Antworten an
  `req`/`status`/`body_sha256` gebunden, auch bei Fehlern und `304`; jeder kc-Endpunkt prüft den
  Anker gegen das, was er adressiert; `keycloakOnly` lehnt DPoP ab; Kontowechsel auf bestehendem
  Kanal → 409; `IdentityResolver`/`absorbProvisionalAccount`/`linkDeviceToAccount` nur im Executor;
  `applyOutcome` verlangt rollen-passende Outcomes; alle Zähler als ein `UPDATE`; `KcTokenProvider`
  je Login eine Keycloak-Sitzung, Tokens bei jeder Evidenzänderung gelöscht; `ProductionModeCheck`
  und `DeploymentTopologyCheck`; `ReadinessGateFilter`; Retention von innen nach außen, auch
  `auth_invite` und `ident_nect` mit `ToolSessionSweeper`.
- **Grant, Federation, Kontolöschung, Bootstrap, Realm V1–V6, QR-Status, Templates,
  Vertragsbindung** – unverändert tragfähig; `AccountRemoval` verlangt `manage-users`, räumt auch
  persistierte Sitzungen und Login-Failures; Kontolöschung übersteht einen Realm-Neuaufbau
  (4095649).
- **Codequalität.** Kein TODO/FIXME, kein `@Deprecated`; `!!` 21 (unverändert), `lateinit` 2,
  `@Suppress` 3 (alle `UNCHECKED_CAST`); `else ->` bei sealed-Subjekten weiterhin nur die vier aus
  Q-10 alt; `auth_invite` folgt dem Controller-Muster (201 + Location, `read()` wie die 21
  Geschwister, OpenAPI-Beispiele, Snapshot); Erweiterung nach der Aufteilung ohne Duplikate und
  ohne tote Klassen (alle unreferenzierten sind SPI-Factories); Versionen zentral und aktuell.
- **CI.** Build mit Tests, Vitest, `tsc -b --force`, `npm audit`, OpenAPI-Snapshot und
  -Kompatibilität, Generatorstand per `git diff --exit-code`, Playwright gegen eigenen Server,
  CodeQL über alle drei Module.

## 6. Kennzahlen

- Kotlin-Dateien main / test: 497 / 206; Zeilen 32 301 / 26 294 (dritte Bewertung: 474 / 176,
  30 867 / 22 008).
- Keycloak-Erweiterung: 72 Java-Dateien / 5 700 Zeilen main in 9 Paketen; 22 Testklassen /
  1 681 Zeilen / 94 `@Test`; 25 FTL. Migrationen: 6 Skripte / 952 Zeilen.
- Modulith-Module 22; ADR-Dateien 44, alle im Index; Invarianten 31; Journeys 11. `tool_api`
  2 034 Zeilen (neu: `Subject`, `Attempted`, `Invitations`, `KeycloakToolCalls`).
- Größte Klassen: `JourneyService` 596, `AccountService` 487, `ChannelController` 482,
  `JourneyActionExecutor` 450, `ChannelService` 405, `ToolJourneyService` 327; Erweiterung
  `OrchestratorClient` 526, `OrchestratorAuthenticator` 296, `ManageMethodsRequiredAction` 222.
- Funktionen über 60 Zeilen: Kotlin 1 (`RegisterStrategy.transition` 71); Java 4
  (`OrchestratorAuthenticator.handleResponse` 98, `ManageMethodsRequiredAction.processAction` 74,
  `AccountTokenGrantType.process` 74, `ManageMethodsRequiredAction.handleResponse` 62).
- Tests: 148 `BehaviorSpec`, 56 Spring-Specs, 6 Kontext-Konfigurationen; `Thread.sleep` in 4
  Polling-Helfern; `relaxed = true` 103 (vorher 115); Tool-Module ohne Unit-Test: 1.
- `!!` 21, `runCatching` 22, `else ->` 80, Text-Vorlagen 79 `ae/oe/ue` / 112 Umlaute.
  Kover-Sperrklinke 82 %. Compiler-Warnungen nicht erhoben (kein Gradle-Lauf in dieser Runde).

## 7. Nicht verifiziert

- S-1 ist am Code beider Seiten belegt (Flow-Position des Resume, Claims ohne Zeit, `afterProof`,
  `LoginCompletion`), aber nicht gegen ein laufendes Keycloak durchgespielt. Ein
  Integrationstest mit gestellter Uhr würde es entscheiden.
- K-1: dass `DefaultBruteForceProtector.failedLogin` für einen föderierten Nutzer bucht – nach
  Quelle plausibel, nicht gemessen. K-2: am Keycloak-Quelltext (`CodeGenerateUtil`,
  `SessionCodeChecks`) belegt, nicht gegen compose-Keycloak gespielt.
- A-1: ob Keycloak für einen Einladungs-Nutzer ein `LOGOUT`-Ereignis mit `userId` und
  `sessionId` liefert – nach Quelle ja.
- K-6: Nimbus-Voreinstellungen aus der Bibliotheksdokumentation. Zeitverhalten von S-7 nicht
  gemessen. Abhängigkeiten nicht gegen einen CVE-Feed geprüft. Kein Gradle-Lauf: die genannten
  Tests wurden gelesen, nicht ausgeführt.

## 8. Stand der Umsetzung (2026-09-30)

Alle Befunde der Stufe „mittel“ sind bearbeitet; einen hohen gab es nicht. Entscheidungen des
Inhabers vorab: S-1 im Policy-Modell (gilt für beide Kanäle), A-1 mit eigenem Endpunkt und Eintrag im
Anmeldeprotokoll, A-2 mit Wächter und `subject` in der Anfrage, K-2 mit `returnUri` im Retry-PATCH.
Issues unter dem Epic `DPoP-demo-updm`.

**Sicherheit und Keycloak**

- ~~S-1 `loa-max-age` über den Resume-Pfad unterlaufen~~ – erledigt: Jeder Nachweis trägt
  `provenAt`; `DefaultAuthPolicy` zählt über `loa1` nur Nachweise der letzten
  `identity.policy.elevated-level-max-age` (30 min), ältere tragen `loa1`, und das schon benutzte
  Verfahren wird wieder angeboten. RestoreData trägt den Zeitpunkt mit, ein Nachweis ohne ihn gilt
  als beliebig alt. Tests: `DefaultAuthPolicyTest` (29/31 min, unbekanntes Alter),
  `RestoreDataCodecTest`, `KcChannelIntegrationTest` (echter Resume-Pfad mit gealtertem Nachweis).
  Doku: 04 §8 „Ein Nachweis über loa1 altert“, 07 §3, neue Invariante I-32.
- ~~K-1 Orchestrator-Fehler als Fehlversuch~~ – erledigt: `OrchestratorAuthenticator.action` ruft
  nie mehr `failureChallenge`; 4xx zeigt die Meldung, sonst „Anmeldung derzeit nicht möglich.“
  (`ApiFailure`, `ApiFailureTest`).
- ~~K-2 Nect-Retry auf verbrauchter Adresse~~ – erledigt: `WebToolRendererFactory.actionFields`
  gibt beim Retry eine frische Action-URL mit, `IdentNectPatchRequest.returnUri` durchläuft dieselbe
  Präfix-Prüfung und ersetzt die gemerkte. ADR-47 und Port-Vertrag berichtigt. Tests:
  `IdentNectRendererFactoryTest`, `IdentNectToolHandlerTest`. Gegen ein laufendes Keycloak nicht
  gespielt (`DPoP-demo-z90h`).

**Architektur**

- ~~A-2 Einladungskanal im Upsert~~ – erledigt: `KcChannelUpsertRequest.subject` wie
  `authData.subject`; `accountId` ist aus Anfrage und `authData` entfernt (vor dem ersten Release
  keine Kompatibilitätsfelder, `api/published/v1.yaml` neu eingefroren); ein Kanal eines anderen Subjekts
  antwortet `409` und bleibt, wie er ist; eine Einladung bindet nur ihr eigener Nachweis. Die
  Erweiterung schickt `KcSubject`. Test: `AuthInviteIntegrationTest` (Konto, fremde Einladung,
  dieselbe Einladung).
- ~~K-5 anonymer Step-up auf einer Einladungssitzung~~ – erledigt nach Entscheidung des Inhabers
  („nicht aufwertbar“): Der Orchestrator lehnt einen Kanal mit Einladungs-Subjekt, der ihr nicht schon
  gehört, mit `409` und Begründung ab; die Anmeldeseite zeigt sie, der Resume-Schritt überspringt
  Einladungssitzungen. Test: `AuthInviteIntegrationTest`. ADR-48 Nachtrag.
- ~~A-1 Abmeldung eines Einladungs-Nutzers~~ – erledigt: `SignInLogEventListener` meldet
  Abmeldungen beider Federationen, `POST …/kc/invitations/{id}/sign-outs` beendet die Web-Kanäle der
  Sitzung; das Anmeldeprotokoll führt Zeilen einer Einladung (V34, `ck_sign_in_log_one_subject`).
  Tests: `AuthInviteIntegrationTest`, `SignInLogEventListenerTest`. ADR-48 Nachtrag, 05-api, 07.

**Codequalität und Tests**

- ~~Q-2 `auth_invite` ohne Unit-Test~~ – erledigt: `AuthInviteToolHandlerTest` (10 Fälle, auch
  gedrosselt und unbekannte Nummer mit gültigem Code), `AuthInviteFlowTest`.
- ~~Q-1 Handlung im `Then`~~ – erledigt für die fünf genannten Specs. Die Ursache war nicht
  Nachlässigkeit: `IntegrationTestSupport` leerte die Datenbank vor jedem `then`, eine Handlung im
  `when` war dort schon gelöscht. Die Aussage oben, die übrigen 49 Spring-Specs hielten die Regel,
  stimmt nicht; sie handeln aus demselben Grund im `then`. Neu: `resetPerWhen = true` leert vor
  jedem `when`, Stubs über `beforeScenario`; AGENTS.md beschreibt es. Die übrigen Specs stellt
  `DPoP-demo-ooql` um.
