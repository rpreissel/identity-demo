# Offene Befunde

Alle Befunde aus den Bewertungen, die noch offen sind, an einer Stelle: aus der dritten Bewertung
(2026-09-27), der vierten Bewertung (2026-09-29) und dem Sicherheitsaudit (2026-10-03). Geprüft gegen
den Code am 2026-10-04. Was erledigt ist, steht hier nicht mehr; es lebt in der Git-Historie und in
den geschlossenen Issues (Epics `DPoP-demo-9ppv`, `DPoP-demo-updm`, `DPoP-demo-164n`).

So ist die Liste zu lesen:

- **Kürzel** bleiben die der Herkunft, weil Doku, Issues und der
  [Lesepfad Sicherheit](16-lesepfad-sicherheit.md) sie nennen: `S-`, `K-`, `A-`, `Q-` ohne Zusatz
  aus der vierten Bewertung, mit dem Zusatz „(3.)“ aus der dritten, `SA-` aus dem Sicherheitsaudit.
- **Schwere** wie in den Bewertungen: mittel (eine Zusage gilt nicht, realistischer Missbrauch),
  niedrig (begrenzte Wirkung), Hinweis (Hygiene). „Bewusst“ heißt: eine Entscheidung nimmt es in
  Kauf; es steht als benanntes Restrisiko hier.
- **Issue**: `bd show <id>`. „–“ heißt: noch kein Issue.

Maßstab bleibt [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md): Der
Backend-Kern soll produktionsreif sein, jede Sicherheitszusage gilt ohne unbenannte Annahme an die
Umgebung.

---

## 1. Sicherheit im Kern

- **S-2 (niedrig) `ident-nect`: `retry` ohne Budget, Nect-Fälle ohne Aufbewahrung.**
  `IdentNectToolHandler.patch` legt je `retry` einen neuen Fall an, ohne Zähler; `nect.ident_case`
  räumt niemand. Fix: ein `RateLimit` des Moduls (etwa 3 je ToolSession und 10 Minuten, dann `429`)
  und ein Sweeper nach `createdAt`. Issue: –
- **S-3 (3.) (niedrig) Die DPoP-Replay-Tabelle wächst vor Kanal- und Drosselprüfung.** Jeder
  syntaktisch gültige Proof schreibt eine Zeile; Schlüssel kosten nichts. `DPoP-demo-9ppv.1`
- **A-4 (niedrig) „`auth-invite` nur im Web-Kanal“ ist eine umschaltbare Voreinstellung.** Die
  dritte Linie, `JourneyActionExecutor.acceptInvitation`, antwortet mit `check` und damit `500`
  statt `409`. Entscheidung des Inhabers: Kanalbindung als Eigenschaft des Tools (`ChannelType` nach
  `tool_api`) oder bei der Voreinstellung bleiben und die dritte Linie als `invalidState`. Issue: –
- **A-5 (niedrig) Ein erfolgreicher Vorgangszugang setzt den Personenzähler nicht zurück.**
  `ToolJourneyService.chargeRateLimits` setzt bei `Completed.Authenticated` nur das Konto zurück.
  Vier Fehlversuche, ein Erfolg, ein Fehlversuch sperren die Person 15 Minuten, auch für
  `ident-fsc`. Fix: Das Ergebnis muss die Person nennen können (Vertragsänderung, Entscheidung des
  Inhabers). Issue: –
- **Lookup-Orakel (niedrig).** Demo-TAN und, mit echtem Anbieter, die Versandlatenz verraten, ob
  eine Adresse ein Konto hat. `DPoP-demo-36xz`
- **I-23 (niedrig) Kanal-Lebensdauer und Keycloak-Sitzung.** Der Web-Kanal ist `AUTHENTICATED`,
  bevor Keycloak die Sitzung anlegt; Abmeldemeldung und `restore-data` sind „best effort“.
  `DPoP-demo-oe06`
- **I-14 (niedrig) Gerätelink ohne Fremdschlüssel.** `DPoP-demo-hwc6`
- **Test (niedrig) Zwei gleichzeitige `PATCH` auf dieselbe ToolSession** sind über `@Version`
  geschützt, aber nicht getestet. `DPoP-demo-df48`
- **A-16 (3.) (Hinweis) I-10 gilt per Regel nur für den Resolver**, nicht für
  `IdentityMatchingService`. `DPoP-demo-9ppv.23`
- **Hinweis: Einrichten in der Verfahrensverwaltung wird bei Abschluss nicht erneut gegen `loa2`
  geprüft** (`ManageAuthMethodsStrategy`, Zustand `Enrolling`). Altert der Nachweis dazwischen, wird
  das Verfahren mit niedrigerem Niveau eingetragen, nie mit höherem. Kein Handlungsbedarf. Issue: –
- **Hinweis: Der Freischaltcode liegt im simulierten Personenverzeichnis ungesalzen.** Der
  Port-Vertrag sollte die Anforderung an ein echtes System nennen. `DPoP-demo-4xnr`

## 2. Keycloak-Erweiterung und -Anbindung

- **K-3 (niedrig) Die Verfahrensverwaltung aktiviert Tools ohne Renderer-Felder.**
  `OrchestratorManageMethodsRequiredAction` ruft weder `activationFields` noch `actionFields`. Für
  `ident-nect` fehlt damit die Rücksprungadresse, und der Nect-Retry dort (K-2 der vierten Bewertung)
  läuft auf eine verbrauchte Adresse. Wurzel sind zwei Dispatcher. `DPoP-demo-9ppv.12`
- **Fehlerpfade des Authenticators (niedrig)** zeigen teils Keycloaks generische Seite.
  `DPoP-demo-rdns`
- **K-6 (Hinweis) Antwort-JWKS mit Nimbus-Voreinstellungen.** `OrchestratorResponseVerifier`
  nutzt `JWKSourceBuilder.create(…).retrying(true)`: 500 ms Zeitlimit, kein `outageTolerant`. Fix:
  eigener `ResourceRetriever` mit 3 s/10 s und Größenlimit, `outageTolerant`. Issue: –
- **K-7 / K-9 (3.) (Hinweis) JSON per `?no_esc` in `<script>`** der Demo-Personenauswahl, nur
  Seed-Daten im Demomodus. `DPoP-demo-9ppv.11`
- **K-8 (Hinweis, bewusst) Jeder GET auf die Action-URL wird Tool-Eingabe**, auch
  `orchestrator_back`/`orchestrator_abandon`. Braucht Aktionscode und Cookie. Option: nur für Tools
  mit `activationFields`. Issue: –
- **K-8 (3.) (Hinweis) QR-Status-Endpunkt ohne Mindestintervall.** `DPoP-demo-9ppv.10`
- **K-14 (3.) (Hinweis) Peer-Auth-Fenster 300 s im Profil `keycloak`** statt nur in der Variante
  `host`. `DPoP-demo-9ppv.13`
- **Hinweis: Die Kanal-Id des Web-Kanals ist aus der Tab-Id abgeleitet**, also vorhersagbar;
  Zugriff verlangt trotzdem eine signierte Assertion mit passendem `channel_binding`.
  `DPoP-demo-gxis`
- **SA-21 (Hinweis) Redirect-URIs mit Wildcard** (`…/*` in `application-keycloak.yml`). Ändert das
  Realm-Setup und erzwingt einen Neuaufbau; erst die Pfade der SPA klären. `DPoP-demo-164n.21`
- **`loa3` im Web-Realm (offen, Entscheidung).** `DPoP-demo-wzcm`

## 3. Architektur

- **A-3 (niedrig) Die Paketaufteilung der Erweiterung hat Zyklen und keine Prüfregel.**
  `client ↔ federation`, `federation ↔ login`, `login ↔ resource`, `login ↔ token`. Fix:
  Wire-Records in ein Blatt-Paket, ArchUnit-Regel `beFreeOfCycles` im Erweiterungsbuild. Issue: –
- **A-6 (Hinweis) Die Erweiterung liest die Uhr selbst** (`PeerAuthAssertionSigner`,
  `OrchestratorResponseVerifier`, `OrchestratorSettings`). Die Zeitregeln der Peer-Auth sind dort
  nur mit echten Wartezeiten testbar. Fix: `Clock` als Konstruktorparameter. Issue: –
- **A-7 (Hinweis) Drei Formen für „wer“ in `tool_api`** (`Subject`, `Attempted`, `AuthSubject`).
  Fachlich verschieden, aber `Subject.Invitation.hash` neben „Id der Einladung“ stolpert. Issue: –
- **A-8 (Hinweis) Die Prüfung von Assertion und `channel_binding` ist in den kc-Controllern
  wiederholt** (`KeycloakAccountLookupController`, `KeycloakInvitationLookupController`,
  `KeycloakSignOutController`). Fix: ein gemeinsamer Helfer am `PeerAuthValidator`. Issue: –
- **Aus der dritten Bewertung:** A-7 Modulabhängigkeiten per Test (`DPoP-demo-9ppv.15`), A-8 tote
  Enum-Werte und CHECKs für Zustände (`9ppv.16`), A-11 gemeinsame Wurzel der REGISTER-Zustände
  (`9ppv.19`), A-13 `DemoStepReason` aus dem Fachkern (`9ppv.21`), A-14 nur `InvalidInputException`
  wird `400` (`9ppv.22`), K-5 ArchUnit-Regel „keine Transaktion um Keycloak-Aufrufe“ (`9ppv.7`).

## 4. Codequalität und Tests

- **Q-4 (niedrig) `!!` auf dem gerade geprüften Feld** in `AuthInviteFlow` und
  `AuthPasswordLookupFlow`; 21 `!!` insgesamt. Mit Q-8 (3.) `DPoP-demo-9ppv.27`
- **Q-5 (niedrig) Testhelfer mehrfach definiert**, `IntegrationTestSupport` groß. Mit Q-13 (3.)
  `DPoP-demo-9ppv.32`
- **Q-6 / K-10 (niedrig) Dispatch-Duplikate und Testlücken der Erweiterung.** Ohne Test: beide
  Dispatcher, Resume- und Update-Authenticator, `WebFormRenderer`, die meisten Renderer-Factories.
  `DPoP-demo-9ppv.12`
- **Q-7 (Hinweis) `ident_eid` ist vom Kover-Tor ausgenommen**, trägt aber Kernlogik (`IdentEidFlow`).
  Fix: Ausschluss auf `simulation.*` beschränken. Issue: –
- **Q-8 / Q-11 (3.) (Hinweis) Umlaute in Text-Vorlagen gemischt.** `DPoP-demo-9ppv.30`
- **Q-9 (Hinweis) `e2e-keycloak` läuft nicht in der CI.** Lokal gegen compose grün (2026-10-04).
  Ein nächtlicher Job mit `podman compose` wäre der Weg. Issue: –
- **Q-10 (Hinweis) `RegisterStrategy.transition` mit 71 Zeilen**, ein `when` über acht Zustände;
  kein Handlungsbedarf.
- **Aus der dritten Bewertung:** Q-5 `auth_email` löst das Konto im Controller auf
  (`DPoP-demo-9ppv.24`), Q-6 QR-Controller mit OpenAPI-Beispielen (`9ppv.25`), Q-7 ein Weg zum
  Journey/Kanal-Paar (`9ppv.26`), Q-9 `RestoreDataCodec` fängt zu breit (`9ppv.28`), Q-10 `else`
  bei sealed-Subjekten (`9ppv.29`), Q-12 tote Deklarationen (`9ppv.31`), Q-14 lange KDocs
  (`9ppv.33`), Q-16 `relaxed`-Mocks (`9ppv.34`), Q-17 `allWarningsAsErrors` (`9ppv.35`).

## 5. Umgebung und Betrieb

- **SA-25 (Hinweis) Keine NetworkPolicy auf OpenShift**; Management-Ports sind aus anderen Pods
  erreichbar. `DPoP-demo-164n.25`
- **S-9 (3.) (Hinweis) Keycloak-Bootstrap-Admin nicht im Startcheck**, `DEMO_MODE`-Hinweis in der
  Doku. `DPoP-demo-9ppv.3`; fehlendes `demo.mode` gilt als Demomodus (`DPoP-demo-davx`).
- **S-12 (3.) (Hinweis) Laufzeit-Image in compose nicht gepinnt.** `DPoP-demo-9ppv.5`
- **Umgebung:** TLS Keycloak ↔ Orchestrator und Proxy-Header (`DPoP-demo-ai4x`); Keycloak
  `start --optimized` (`DPoP-demo-9msv`); Admin-Geheimnis auf OpenShift (`DPoP-demo-x25a`);
  PostgreSQL (`DPoP-demo-pi55`); gemeinsame Sperre für geplante Aufgaben (`DPoP-demo-g7np`); Backup
  und Restore (`DPoP-demo-prnl`); Frontend: CSP, Tokens im Browser, `state`/`nonce`
  (`DPoP-demo-dm2j`).

## 6. Bewusst in Kauf genommen

- **SA-27 Die Konto- und Personensperre prüft vor dem Versuch und zählt danach.** Parallele Versuche
  über mehrere Kanäle kommen alle durch die Prüfung, bevor der fünfte zählt. Praktisch betrifft das
  Passwörter, die `PasswordPolicy` und Argon2 schützen. Vor einer produktiven Passwortanmeldung per
  Lookup nachzuholen ([07-betrieb.md](07-betrieb.md) Abschnitt 4). `DPoP-demo-164n.29` (deferred)
- **S-7 `auth-invite` und `ident-fsc`: Eine unbekannte Nummer kostet nichts, eine bekannte
  antwortet messbar anders.** Das Kennwort selbst ist nicht ratbar. Mit `DPoP-demo-36xz`.
- **S-8 Keycloaks Action-URL samt Aktionscode liegt am Nect-Fall und geht an das Fremdsystem**
  (ADR-47). Option: eigene Rücksprungadresse am Orchestrator.
- **Das `acr` im Token altert nicht** (SA-5): Es beschreibt wie bei Keycloak die Anmeldung
  ([04-orchestrierung.md](04-orchestrierung.md) Abschnitt 8). Anwendungen prüfen `acr` und, wenn
  sie Frische brauchen, `auth_time` (`DPoP-demo-mea0`).
- Weitere bewusste Punkte (DPoP ohne Nonce, Tokens nicht an DPoP gebunden, KOBIL-PIN im Klartext)
  führt der [Lesepfad Sicherheit](16-lesepfad-sicherheit.md) in seinem Abschnitt 15.

## 7. Offene Entscheidungen des Inhabers

- **Passwortwechsel:** Soll die Verfahrensverwaltung `enroll-password` bei aktivem Passwort als
  „ersetzen“ anbieten? Darauf bauen eine Required Action `orchestrator-change-password`, der Anstoß
  durch den Admin per `execute-actions-email` (`DPoP-demo-164n.27`) und die Account-Konsole für
  föderierte Nutzer (`DPoP-demo-164n.28`).
- **A-4** Kanalbindung eines Tools, **A-5** Person am Anmeldeergebnis (oben).
- **Schlüsselverwaltung** (`DPoP-demo-61kp`), **Verschlüsselung personenbezogener Spalten**
  (`DPoP-demo-bo1w`), **Aufwerten nach erneuter Identifizierung** (`DPoP-demo-wyp3`), **`loa3` im
  Web-Realm** (`DPoP-demo-wzcm`).
- **Echte Fremdsysteme:** Nect (`DPoP-demo-v033`, `DPoP-demo-z90h`).
