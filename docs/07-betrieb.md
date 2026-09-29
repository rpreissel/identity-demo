# Fehler, Konsistenz und Lebenszyklus

Dieses Kapitel beschreibt den Fehlervertrag, was transaktional zugesagt ist und wie lange welche
Daten aufbewahrt werden.

> **Einschränkung ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)):**
> Produktionsreif ist der Backend-Kern. Frontends und Ausführungsumgebung (`compose.yml`,
> `openshift/`, Admin-Zugang, H2-Konsole, Keycloak-Startmodus, TLS zwischen den Containern) sind
> Vorführrahmen und werden später gehärtet. Bis dahin läuft keine Instanz mit echten Personendaten.

---

## 1) Fehlervertrag

Die üblichen Fehlerantworten:

- `400 Bad Request`: ungültiger Inhalt oder formal ungültige Anfrage.
- `401 Unauthorized`: DPoP-Nachweis fehlt oder ist ungültig, oder dem Kanal wird nicht vertraut.
  Bei DPoP- und Geräte-Proofs nennt der Text nur einen festen Grund (`DpopFailure`, etwa
  `IAT_IN_FUTURE` bei vorgehender Uhr), bei der Peer-Auth-Assertion von Keycloak gar keinen.
  Schlüssel-Id, Aussteller, Algorithmus und Claim-Werte stehen nur im Log.
- `403 Forbidden`: Die Bindung passt nicht, oder eine Regel verbietet die Aktion.
- `404 Not Found`: Sitzung oder Vorgang unbekannt.
- `409 Conflict`: unzulässiger Zustandswechsel, nicht erlaubte Aktion, ein zweiter gleichzeitiger
  Vorgang auf derselben `ChannelSession` oder widersprüchliche Angaben zum Konto.
- `410 Gone`: Der Vorgang ist abgelaufen, bereits verbraucht oder nach zu vielen Fehlversuchen
  abgebrochen.
- `422 Unprocessable Entity`: fachlich nicht verarbeitbar, ohne dass der Nutzer etwas falsch
  eingegeben hat (z. B. unbekannte `enrollmentRef`, fehlende Einrichtung).
- `423 Locked`: Das Konto ist nach zu vielen fehlgeschlagenen Anmeldeversuchen gesperrt
  (`ACCOUNT_LOCKED`, `OrchestratorException.accountLocked()`, Abschnitt 4).
- `429 Too Many Requests`: Eine Mengenbegrenzung ist erreicht; das Konto ist dabei nicht gesperrt
  (`OrchestratorException.tooManyRequests()`, Abschnitt 4: `ChannelCreationThrottleService`,
  gezählt je `bindingKeyRef`).
- `500 Internal Server Error`: Eine interne Annahme ist verletzt oder etwas ist unerwartet
  fehlgeschlagen, z. B. die Datenbank nicht erreichbar (`INTERNAL_ERROR`). Die Antwort enthält eine
  feste Text-Referenz; die Einzelheiten stehen nur im Log. Jede Exception, für die es keine eigene
  Regel gibt, landet hier (`OrchestratorExceptionHandler.handleUnexpected`). Ausgenommen sind nur
  Springs eigene Web-Fehler (unbekannter Pfad `404`, falsche Methode `405`, falscher Inhaltstyp
  `415`), die ihren Status selbst mitbringen.

**Form und Quelle.** Jede Fehlerantwort hat die Form `ErrorResponse`
(`{"error": "<CODE>", "text": {"key": …, "args": …}}`; der Text ist eine Referenz wie in
[05-api.md](05-api.md), Abschnitt „Texte“). So steht sie auch im Vertrag, als `default`-Antwort
jeder Operation. Welcher Code zu welchem Status gehört, legt das Enum `ErrorCode` fest
(`orchestrator/domain`), und die Liste im Vertrag wird daraus erzeugt. Code und Status lassen sich
deshalb nicht unabhängig voneinander wählen. Clients entscheiden anhand von `error`, nie
anhand des Textes, und müssen mit Codes rechnen, die sie noch nicht kennen.

**Welche Exception wozu führt.** Auf diese Regel verlässt sich die zentrale Fehlerbehandlung:

- `require` bzw. `IllegalArgumentException` nur, wenn eine **Eingabe des Clients** abgelehnt wird.
  Das ergibt `400`. Einen eigenen Text für den Nutzer trägt nur `InvalidInputException` (etwa
  „Das Passwort ist zu kurz“); jede andere Ablehnung bekommt den festen Text „Die Eingabe ist
  ungültig.“, ihre Meldung steht nur im Log.
- `check`, `checkNotNull` und `error()`, wenn eine **interne Annahme** verletzt ist. Das ergibt
  `500` mit festem Text. So sieht eine interne Prüfung nicht wie ein fachlicher Konflikt aus und
  verrät keine Interna.
- Ein echter fachlicher Konflikt wird immer ausdrücklich gemeldet:
  `OrchestratorException.invalidState(...)`.

**Keine Framework-Texte in Fehlerantworten.** Was in `text` steht, ist immer ein eigener Text des
Projekts (`Text("…")`), nie die Meldung einer Exception aus Spring, Hibernate, Jackson oder einer
anderen Bibliothek. Solche Meldungen nennen oft Klassen, Spalten, Constraints oder interne IDs.
`OrchestratorExceptionHandler` hält das so: Ein nicht lesbarer Body wird zu „Die Anfrage ist nicht
lesbar.“, ein falsch geformter Pfad- oder Query-Wert nennt nur den Namen des Parameters, eine
Constraint-Verletzung beim Binden eines Kontos wird zu einem festen Konflikttext, und alles andere
landet mit festem Text in `500`. Die Originalmeldung geht jeweils nur ins Log.

Ausdrücklich **kein** Fehler sind fehlende Pflichtfelder und Fehlversuche, nach denen noch weitere
Versuche erlaubt sind. Sie liefern `200` und ein `next` (Regel für Wiederholungen in
[Orchestrierung](04-orchestrierung.md)).

## 2) Konsistenzregeln

- Je `ChannelSession` darf höchstens eine **laufende** `AuthJourney` existieren. Eine Journey, die
  auf eine Sub-Journey wartet, ist `SUSPENDED` und zählt nicht mit
  ([Orchestrierung](04-orchestrierung.md)).
- Eine `AuthJourney` darf nur in gültige Folgezustände wechseln, sowohl im Lebenszyklus als auch im
  Zustand ihres Intents (`JourneyState`).
- `AuthContext` wird nur aktualisiert, wenn eine Journey erfolgreich abgeschlossen ist.
- Jede Identifizierung, jeder Widerruf und jede eingerichtete oder deaktivierte Methode erzeugt einen Eintrag im Änderungsprotokoll des Kontos (`account.change_log`, [ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md)); jeder Übergang einer Journey einen Eintrag im Journey-Trace.
- **Eine Transaktion für alles:** Verarbeitet der Orchestrator ein `ToolOutcome.Completed`
  ([Orchestrierung](04-orchestrierung.md)), speichert er in einer einzigen Transaktion den neuen
  Journey-Zustand, den Konto-Eintrag, das Claim-Log und den Nachweis der Sitzung (`AuthEvidence`).
  Entweder gelingt alles oder nichts. Das Methodenmodul speichert seine Tool- und
  Einrichtungsdaten schon beim `PATCH` in einer eigenen Transaktion. Scheitert danach der Schritt
  in der Journey, bleibt die Zeile des Moduls zwar stehen, wird aber nicht als Credential des
  Kontos aktiviert.
- Auch neue Konten, Claim-Log, Identifizierungs-Log, Anker und Methodeninstanzen liegen in dieser
  Transaktion. Ein Konto wird nicht vorab in einer eigenen Transaktion (`REQUIRES_NEW`)
  festgeschrieben. Binden zwei Vorgänge gleichzeitig, wird der unterlegene vollständig
  zurückgerollt und erhält `409 INVALID_STATE_TRANSITION`; einen automatischen neuen Versuch gibt
  es nicht. Verstöße gegen die Eindeutigkeit von `ux_anchor_value` und `ux_anchor_account_type`
  werden auch dann gezielt übersetzt, wenn sie erst beim Schreiben oder Festschreiben auffallen.
  Unbekannte Integritätsfehler bleiben Serverfehler.
- Nicht transaktional ist der SMS-Versand, denn er wirkt nach außen: Ein Zurückrollen macht eine
  bereits verschickte SMS nicht rückgängig. Das betrifft nur die Zustellung, nicht die Konsistenz.
  Die zugehörige Zeile mit `issuedTanHash` wird mit zurückgerollt, und der Code in der SMS passt
  zu nichts mehr.

## 3) Aufbewahrung und Löschung

Die Daten einer Sitzung enthalten Personenbezug (KVNR, Name, Telefonnummer) und Werte, die aus
Geheimnissen abgeleitet sind (`issuedTanHash`). Nach dem Ende des Vorgangs werden sie nie wieder
gelesen. Deshalb werden sie aktiv gelöscht und nicht aufbewahrt.

Richtwerte (als Voreinstellung gedacht, nicht als Vorgabe für Compliance):

- **`<modul>.*_tool_session` (Moduldaten)**
  - *Frist beginnt mit:* `createdAt`
  - *Richtwert:* 24 h (`tool-session.retention`)
  - *Grund:* Personenbezug und TAN-Hash. Jedes Methodenmodul löscht seine eigenen Tabellen selbst (`*RetentionJob` implementiert `ToolSessionSweeper`); Frist und Intervall stehen dagegen nur einmal, in `orchestrator/retention/ToolSessionRetention.kt`. Bei `auth_kobil.enroll_tool_session` ist die Frist besonders wichtig: Dort liegen während einer laufenden Einrichtung KOBIL-PIN und Entsperrgeheimnis im Klartext ([ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md))
- **`kobil.*` (Fremdsystem)**
  - *Frist beginnt mit:* —
  - *Richtwert:* **kein** Aufräumen durch uns
  - *Grund:* `kobil` simuliert KOBIL und unterliegt nicht unseren Aufbewahrungsregeln. Dass es seine Daten überhaupt speichert, ist nötig und keine Bequemlichkeit: Sonst würde nach jedem Neustart jede `auth_kobil.enrollment`-Zeile auf einen Nutzer zeigen, den es beim Anbieter nicht mehr gibt
- **`nect.*`, `personenverzeichnis.*` (Fremdsysteme)**
  - *Frist beginnt mit:* —
  - *Richtwert:* **kein** Aufräumen durch uns
  - *Grund:* simulierte Fremdsysteme wie `kobil`. Auch die Briefe des Personenverzeichnisses mit den Freischaltcodes im Klartext bleiben dort, wie Papier beim Empfänger
- **`QrLoginRequest`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* 24 h
  - *Grund:* Kopplungsanfrage, nach Ablauf (5 Min.) wirkungslos; `AuthQrRetentionJob` räumt sie mit auf
- **`DpopProofReplay`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* sofort (minütlich)
  - *Grund:* Der Schutz vor wiederholten Proofs gilt nur in dem Zeitfenster, in dem ein Proof angenommen wird
- **`orchestrator.tool_session`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* 24 h
  - *Grund:* nur Angaben zum Lebenszyklus
- **`AuthJourney`**
  - *Frist beginnt mit:* `consumedAt` / `expiresAt`
  - *Richtwert:* 7 Tage
  - *Grund:* Zuordnung bei Rückfragen an den Support
- **`AuthContext`**
  - *Frist beginnt mit:* Abmeldung / Ende der `ChannelSession`
  - *Richtwert:* sofort
  - *Grund:* enthält Verweise auf Tokens
- **`ChannelSession`**
  - *Frist beginnt mit:* `expiresAt` / `LOGGED_OUT`
  - *Richtwert:* 14 Tage
  - *Grund:* `JourneyTraceEntry` fragt das Log über die Menge der Kanäle ab ([Domänenmodell](02-domaenenmodell.md) Abschnitt 5); länger als das Protokoll selbst (14 Tage) bringt das nichts. Bei einer Sitzung je App-Start ist es eine große Tabelle; gelöscht wird je Stapel mit einer Anweisung je Tabelle
- **`JourneyTraceEntry`**
  - *Frist beginnt mit:* `createdAt`
  - *Richtwert:* 14 Tage
  - *Grund:* Ablaufprotokoll für Fehlersuche, Support und Demo, NICHT das Änderungsprotokoll. Mit Abstand die volumenstärkste Tabelle (eine Zeile je Schritt); 14 Tage decken Support-Fälle ab, länger ist über den Zweck nicht zu begründen
- **`account.change_log`**
  - *Frist beginnt mit:* Löschung des Kontos (`ACCOUNT_DELETED`)
  - *Richtwert:* 10 Jahre (`account.change-log.retention-years`, von der Datenschutzbeauftragten zu bestätigen)
  - *Grund:* Nachweis, dass und wie ein Konto identifiziert wurde und welche Methoden es hatte – ohne Werte, ohne Fremdschlüssel, überlebt die Löschung bewusst ([ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md)); `ChangeLogRetention` räumt ab
  - *Suche:* über Name, Vorname und Geburtsdatum (`ChangeLogSearch`, Suchschlüssel als HMAC). Das Geheimnis `CHANGE_LOG_LOOKUP_SECRET` ist außerhalb des Demomodus Pflicht und muss so lange aufbewahrt werden wie das Protokoll. *Wechsel:* Jeder Eintrag merkt sich die Id des Geheimnisses (`lookup_key_id`). Zum Wechseln das alte Geheimnis unter `account.change-log.previous-lookup-secrets.<alte Id>` eintragen und das neue mit neuer Id setzen (`CHANGE_LOG_LOOKUP_KEY_ID`); gesucht wird mit allen. Das alte darf erst weg, wenn kein Eintrag mehr seine Id trägt – sonst verweigert `ProductionModeCheck` außerhalb des Demomodus den Start. Ohne `CHANGE_LOG_LOOKUP_SECRET` gilt ein öffentlicher Demo-Wert, den `ProductionModeCheck` außerhalb des Demomodus ebenfalls ablehnt; `account` selbst kennt den Demomodus nicht
- **`account.sign_in_log`**
  - *Frist beginnt mit:* dem Ereignis
  - *Richtwert:* 6 Monate (`account.sign-in-log.retention-months`)
  - *Grund:* wer sich wann womit angemeldet hat, Fehlversuche, Sperren und Logouts – für die Aufklärung einer Kontoübernahme; Verhaltensdaten, deshalb kurz und mit dem Konto gelöscht (ADR-39, Nachtrag); `SignInLogRetention` räumt ab – in Stapeln zu 500 Zeilen, jeder in einer eigenen Transaktion
- **`*Enrollment` (Credentials der Module)**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* lebt bis zur Löschung des Kontos (erreichbar über `account.auth_method`, auch deaktivierte Instanzen)
- **`account.*` (Anker, Methoden, Claim-, Identifizierungs- und Widerrufs-Log)**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* gehört dem Konto und wird mit ihm gelöscht – mit allen Werten. Was die Löschung überlebt, ist nur `account.change_log` (ADR-39)
- **Konto im Aufbau** (noch kein Anmeldeverfahren)
  - *Frist beginnt mit:* `createdAt`, sobald kein offener Kanal mehr damit arbeitet
  - *Richtwert:* frühestens nach 1 h, stündlich (`RetentionJob`)
  - *Grund:* eine Registrierung, die ohne Abbruch endete, etwa weil die App geschlossen wurde. Ein Abbruch verwirft sein Konto selbst; der Job räumt den Rest ab, vollständig über `AccountDeletionService` ([ADR-46](adr/ADR-046-konto-im-aufbau.md))
- **`DeviceAccountLink`**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* Identität des Geräts (`bindingKeyRef -> accountId`), überlebt bewusst jede einzelne `ChannelSession` ([DPoP-Bindung](09-dpop.md) Abschnitt 3)
- **`AttemptThrottle`**
  - *Frist beginnt mit:* letzte Änderung des Zählers
  - *Richtwert:* 7 Tage
  - *Grund:* weit länger als das längste Zählfenster und die längste Sperre (15 Min.); ein Aufräumlauf löscht nie eine Zeile, deren Sperre noch läuft. Gilt für die Zähler aller Bereiche (`ACCOUNT`/`PERSON`/`BINDING_KEY`/`ADMIN` und die Versandbudgets der Module, Abschnitt 4)

Wie mit den Verweisen zwischen den Tabellen umgegangen wird:

- **Besitzkette** (`ChannelSession` → `AuthJourney` → `orchestrator.tool_session` → der Teil im
  Modul, `<modul>.*_tool_session`): Sie wird von innen nach außen aufgeräumt. Weil die Fristen von
  innen nach außen länger werden, ergibt sich diese Reihenfolge von selbst.
- **Daten der Module:** Welche Zeilen gelöscht werden, entscheidet jedes Modul selbst. Nur das Modul
  weiß, welche seiner Tabellen zur Sitzung gehören und welche (`*_enrollment`) zum Konto. Wann
  gelöscht wird, steht dagegen an einer einzigen Stelle (`tool-session.retention`), und ein
  gemeinsamer Zeitplaner (`ToolSessionRetentionDriver`) stößt es an.

  `ToolSessionCoverageTest` prüft gegen das tatsächliche Schema, dass ein Aufräumlauf jede
  `*_tool_session`-Tabelle leert, auch eine später hinzukommende. Fehlte einem Modul der
  Aufräumlauf, fiele das sonst niemandem auf, weil nichts fehlschlägt.

  Scheitert der Aufräumlauf eines Moduls, laufen die übrigen trotzdem. Sonst würden Daten länger
  aufbewahrt als erlaubt, nur weil an anderer Stelle ein Fehler auftrat.
- **Das Änderungsprotokoll hängt an keiner anderen Tabelle:** `account.change_log` speichert die `accountId` als
  historischen Wert, nicht als Fremdschlüssel – das Protokoll muss die Löschung des Kontos
  überleben. Eine Id, die auf kein Konto mehr zeigt, ist deshalb erwartet und kein Fehler.
- **Bei KOBIL betrifft das Widerrufen eines Verfahrens auch den Anbieter.** `KobilEnrollmentCleanup`
  löscht nicht nur unsere Zeile, sondern entfernt auch den Nutzer beim Anbieter. Sonst bliebe dort
  ein gebundenes Gerät stehen, von dem bei uns niemand mehr weiß. Dieser zweite Aufruf wirkt nach
  außen und liegt deshalb außerhalb der Transaktion, genau wie der simulierte SMS-Versand. Unsere
  Zeile verschwindet in jedem Fall.
- **Objekte des Kontos sind beim Aufräumen der Sitzungen tabu:** Die Credentials der Module
  (`*_enrollment`), `account.auth_method`, `account.change_log` (IDENTIFIED) und `DeviceAccountLink` gehören
  dem Konto bzw. dem Gerät, nicht der Sitzung.
- **Wird ein Konto gelöscht, räumt das zusätzlich zwei Sitzungstabellen für diese `accountId` auf**,
  obwohl keine von beiden einen Fremdschlüssel auf `account` hat:
  - `orchestrator.journey_trace`, und zwar über **zwei** Schlüssel: das Konto **und** seine
    `ChannelSession`s, weil Einträge aus der Zeit, bevor die Sitzung einem Konto zugeordnet war,
    `account_id = NULL` haben;
  - `orchestrator.attempt_throttle`, aber nur den Bereich `ACCOUNT`. Würden auch `BINDING_KEY` oder
    die Versandbudgets gelöscht, ließen sich diese Zähler durch eine neue Registrierung
    zurücksetzen. Die Versandbudgets hängen ohnehin an Nummer oder Adresse, nicht am Konto.

  `AccountDeletionService.deleteAccount` erledigt das ausdrücklich und unabhängig von den Fristen
  oben.
- **`KEYCLOAK`-Kanäle haben dieselbe Aufbewahrungsfrist wie alle anderen.** Die Abmeldung im
  Web-Kanal gehört Keycloak ([05-api.md](05-api.md) Abschnitt 3); Keycloak meldet sie dem
  Orchestrator (`SignInLogEventListener` → `KcChannelService.signedOutAtKeycloak`), und das beendet
  die noch laufenden Kanäle dieser Sitzung sofort, Web- wie App-Kanal. Der Orchestrator fragt
  Keycloak dafür nicht ab.
- **Die Lebensdauer eines angemeldeten Kanals ist die seiner Keycloak-Sitzung**
  ([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)). Die festen Fristen
  (App 24 Stunden, Web 30 Minuten je Anmeldedurchlauf) gelten nur bis `AUTHENTICATED`. Danach ist
  `expiresAt` das Sitzungsfenster, das Keycloak meldet (SSO idle und SSO max des Realms); im App-Kanal
  schiebt jede Erneuerung des Tokens es weiter, auch die bei einer Journey-Interaktion. Wer die
  Sitzungsdauer ändern will, ändert sie im Realm, nicht im Orchestrator. Die Aufbewahrungsfrist oben
  beginnt entsprechend früher.
- **Die Fristen des Realms stehen in der Migration**, nicht in Keycloaks Voreinstellungen
  (keycloak-migrations, `V5__realm_lifetimes.kc.kts`): AccessToken 5 Minuten, SSO idle 30 Minuten,
  SSO max 10 Stunden, `sslRequired=external`. Die ersten beiden gleichen den Fristen von
  `TokenService` im Standardprofil. Ein erreichtes loa2 trägt 30 Minuten (`loa-max-age` des
  LoA-2-Subflows); danach übernimmt Keycloak es nicht mehr aus der SSO-Sitzung, und eine Anfrage
  mit `acr_values=2` verlangt einen frischen Nachweis. loa1 trägt die ganze Sitzung.

Im Demomodus gilt beim Start außerdem: Hat der Orchestrator kein einziges Konto, etwa nach einem
frischen Volume oder einer neu aufgesetzten Datenbank, gehört jede Sitzung in Keycloak zu einem Konto,
das es nicht mehr gibt. Er meldet dann in Keycloak alle ab (`KeycloakOrphanSessionsAtStart`).

## 3a) Keycloak liest die Konten – keine Spiegelung

Keycloak hält keine Kopie der Konten. Seine Nutzer-Federation (`OrchestratorStorageProvider`, ohne
Import) liest ein Konto bei Bedarf beim Orchestrator nach (`KcAccountLookupController`): nach
Konto-Id, exakter E-Mail-Adresse oder Benutzername, jeweils ein einzelner Zugriff über Primärschlüssel
oder den eindeutigen E-Mail-Anker. Eine Liste aller Konten gibt es nicht; die Suche der Admin-Konsole
findet nur exakte Treffer ([ADR-38](adr/ADR-038-keycloak-liest-konten.md)).

- **Was Keycloak zeigt:** Benutzername (die bestätigte E-Mail, sonst `account-<id>`), E-Mail, Vor- und
  Nachname und die Attribute hinter den Token-Claims (`personId`, `kvnr`, `versnr`, `birthDate`,
  `streetAddress`, `postalCode`, `locality`; im Token `birth_date`, `street_address`, `postal_code`, `locality`). Für ein Konto mit Person gelten nur die Werte des Personenverzeichnisses,
  für einen Interessenten der stärkste bestätigte Wert aus dem Konto; ein Konto ohne beides zeigt
  Platzhalternamen. Alles davon ist in Keycloak schreibgeschützt.
- **Frische:** Keycloak cacht einen föderierten Nutzer höchstens 60 Sekunden (Migration V2). Eine
  geänderte Adresse oder ein geänderter Name ist spätestens dann sichtbar.
- **Nutzer-Id und `sub`:** `f:<UUID>:<accountId>`. Die Komponenten-Id ist eine feste UUID
  (`USER_STORAGE_COMPONENT_ID`); eine neu gewürfelte Id würde jedes `sub` ändern.
- **Was Keycloak selbst hält:** Sitzungen, Fehlversuche (Brute-Force-Schutz), Zustimmungen und
  sonstige föderierte Daten eines Nutzers.
- **Konto gelöscht:** `KeycloakAccountRemovalListener` räumt genau diese Keycloak-eigenen Daten ab
  (`DELETE /admin/realms/{realm}/orchestrator-accounts/{accountId}`, `AccountRemoval`). Das ist das
  einzige Ereignis eines Kontos, das Keycloak erreicht; eine Änderung am Konto braucht keinen Aufruf.
- **Einladungen** ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)): Eine zweite
  Nutzer-Federation (`InvitationStorageProvider`, feste UUID `INVITATION_STORAGE_COMPONENT_ID`, Migration
  V6) liest Einladungen des Personenverzeichnisses als eigene Nutzer, nur per Id
  (`KcInvitationLookupController`), Cache ebenfalls 60 Sekunden. Nutzer-Id und `sub` sind
  `f:<UUID der Einladungs-Federation>:<Id der Einladung>`. Der Nutzer trägt die Stammdaten der Person und die Attribute
  `orchestratorInvitation` und `orchestratorProcess` (Claims `invitation` und `process`); er ist nur
  aktiviert, solange die Einladung offen ist. Meldet das Verzeichnis ein Ende (`InvitationEnded`),
  meldet `KeycloakInvitationLogoutListener` den Nutzer ab
  (`POST /admin/realms/{realm}/users/{id}/logout`), über dieselbe Registry wie die Löschung. Ein
  Einladungs-Nutzer hat keine Keycloak-Daten, die aufzuräumen wären, außer seinen Sitzungen.

Das Abräumen nach einer Löschung läuft über die **Event Publication Registry** von Spring Modulith
([ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md)), damit ein fehlgeschlagener
Aufruf nicht spurlos verloren geht:

- Der Listener ist ein `@ApplicationModuleListener`. Bevor die Transaktion der Löschung
  festgeschrieben wird, schreibt Modulith eine Zeile nach `orchestrator.event_publication`.
- Die Zeile wird erst abgeschlossen, wenn die Methode ohne Fehler zurückkehrt. Deshalb fängt der
  Listener Fehler **nicht** ab: Die Exception ist das Signal „nicht erledigt“.
- Zugestellt wird auf Springs gemeinsamem Async-Pool. Eine Löschung ist unabhängig von jeder anderen
  und darf wiederholt werden; eine eigene Spur mit fester Reihenfolge braucht es nicht.
- Offene Zeilen werden nach fünf Minuten erneut zugestellt (`spring.modulith.events.staleness.*`),
  ebenso beim Neustart. Im Profil `keycloak` geschieht das erst nach den Keycloak-Migrationen
  (`KeycloakMigrationRunnerStartup`), nicht parallel dazu: Baut der Demomodus das Realm neu auf,
  liefe eine Löschung sonst gegen ein Realm im Umbau.
- Lehnt Keycloak das zwischengespeicherte Token des Admin-Clients mit 401 ab, etwa nach einem
  Realm-Neuaufbau, holt `KeycloakAdminClient` einmal ein neues und wiederholt den Aufruf.
- Die Zeile enthält den Status, die Zahl der Zustellversuche und den Zeitpunkt der letzten
  Wiederholung.

Was noch offen ist, lässt sich damit abfragen:

```sql
SELECT event_type, listener_id, publication_date, completion_attempts, status
FROM orchestrator.event_publication
WHERE completion_date IS NULL
ORDER BY publication_date;
```

Zwei Dinge sollte man wissen:

- `spring.modulith.events.jdbc.schema: orchestrator` ist zwingend. Ohne diese Einstellung sucht die
  Registry die Tabelle im Standardschema, findet sie nicht und schreibt nichts, ohne jede
  Fehlermeldung. `EventPublicationRegistryTest` prüft deshalb, dass ein fehlschlagender Listener
  wirklich eine offene Zeile hinterlässt.
- Die Tabelle legt Flyway an (`orchestrator/V15__event_publication.sql`), nicht Modulith. Die Datei
  ist unverändert aus dem Jar `spring-modulith-events-jdbc` übernommen. Beim Wechsel auf eine neue
  Modulith-Version muss man sie damit vergleichen.

`KeycloakSessionLogoutListener` nutzt die Registry bewusst nicht. Eine nicht beendete Sitzung in
Keycloak läuft nach wenigen Minuten von selbst ab; die Sitzungen eines gelöschten Kontos dagegen
nicht. Nur der zweite Fall braucht eine Wiederholung.

## 3b) Das System läuft als eine einzige Instanz

Diese Annahme steckt an drei voneinander unabhängigen Stellen:

- **Fünf geplante Jobs** laufen ohne Sperre und ohne Wahl einer führenden Instanz; bei mehreren
  Instanzen liefe jeder Lauf mehrfach parallel. Alle sind idempotent (ein zweiter Lauf löscht, was der
  erste übrig ließ, oder nichts), gleichzeitige Löschläufe auf denselben Zeilen sind aber nicht
  erprobt. Die Liste steht in `SCHEDULED_JOBS` (`DeploymentTopology.kt`); `ScheduledJobsTest` prüft,
  dass sie mit den `@Scheduled`-Methoden übereinstimmt:
  - `RetentionJob`: Sitzungen, Journeys, Ablaufprotokoll, Zähler (stündlich);
  - `ToolSessionRetentionDriver`: Arbeitsdaten der Tool-Sessions aller Module (stündlich);
  - `DpopReplayProtectionService`: Schutz vor wiederholten DPoP-Proofs (minütlich);
  - `ChangeLogRetention`: Änderungsprotokoll gelöschter Konten (täglich);
  - `SignInLogRetention`: Anmeldeprotokoll (täglich).
- `identity.secrets.otp-pepper` ist standardmäßig leer; der Pepper wird also bei jedem Start neu
  gewürfelt. Zwei Instanzen könnten die SMS- und E-Mail-Codes der jeweils anderen nicht prüfen, und
  jede Instanz hätte eigene Zähler für die Versandbudgets.
- `RestoreDataCodec` erzeugt sein Signaturgeheimnis je Prozess; ein RestoreData-Token der einen
  Instanz ist für die andere unlesbar.

Unkritisch für mehrere Instanzen sind dagegen die Zwischenspeicher im `KeycloakAdminClient` (jede
Instanz holt ihr eigenes Token) und `KeycloakAccountRemovalListener`: Er entfernt nur gelöschte
Konten aus Keycloak, und das darf auch doppelt geschehen.

Beim Lesen des Codes fällt das nicht auf, sondern erst mit einer zweiten Instanz, als
gelegentlich fehlschlagende TAN-Prüfung. Deshalb steht es in der Konfiguration:

```yaml
deployment:
  instances: single   # oder: multiple
```

`multiple` schaltet nichts frei. Der Wert beschreibt die Umgebung, und `DeploymentTopologyCheck`
prüft beim Start, ob der Code dafür geeignet ist. Wenn nicht, bricht der Start mit einer Liste
dessen ab, was fehlt. Sobald die Voraussetzungen erfüllt sind (eine gemeinsame Sperre für die Jobs,
ein fest gesetzter Pepper), ist diese Prüfung die Stelle, an der man sie lockert.

## 3c) Außerhalb des Demomodus: was gesetzt sein muss

`demo.mode=false` heißt: Hier dürfen echte Personendaten liegen ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)).
`ProductionModeCheck` bricht dann den Start ab, solange eine Demo-Voreinstellung übrig ist, und nennt
alle auf einmal:

- `demo.admin.password` gesetzt, nicht `admin`, und als Hash (`{bcrypt}…`, `{argon2}…`), nicht im Klartext.
- `spring.h2.console.enabled=false`.
- `identity.secrets.otp-pepper` und `account.change-log.lookup-secret` mit mindestens 32 Zeichen.
- Keycloak über https mit geprüftem Zertifikat (kein `trustSelfSignedCertificate`).
- Keycloak erreicht den Orchestrator über https (`orchestratorBaseUrl` der Keycloak-Einrichtung):
  Über diesen Weg holt Keycloak die Schlüssel, mit denen sich der Orchestrator anmeldet (auch als
  Master-Realm-Client der Migration) und seine Antworten signiert. Das Zertifikat muss Keycloak
  prüfen können.

Außerdem gibt es außerhalb des Demomodus die Demo-Oberflächen nicht (`@DemoSurface`: Mocks der
Fremdsysteme, Kontenverwaltung mit Demo-Reset), keinen Flyway-Reset und keine Demo-Personen.
Admin-Anmeldungen sind gedrosselt: fünf falsche Passwörter für einen Benutzernamen sperren ihn für
15 Minuten (429), auch für das richtige Passwort.

Hinter einem Reverse-Proxy braucht die Prüfung von `htu` (DPoP, Geräte-Beweise, Peer-Auth)
`server.forward-headers-strategy`, damit Schema, Host und Port die des Clients sind. Das ist nur
sicher, wenn ein vertrauenswürdiger Proxy `X-Forwarded-*` jedes Mal überschreibt; sonst setzt ein
Client sie selbst. Verglichen wird nach RFC 9449: Schema und Host ohne Groß-/Kleinschreibung, der
Pfad exakt (`htuMatches`).

## 4) Kontosperre, Mengenbegrenzung und Versanddrosselung (Schutz vor Ausprobieren und Massenversand)

Gemeinsame Grundlage sind `AttemptThrottle` (Entität, Primärschlüssel `(scope, subject)`) und
`AttemptCounter` (`src/main/kotlin/com/example/identity/core/orchestrator/session/`). `scope` trennt die
Zählbereiche: die des Orchestrators (`ThrottleScope`) und die Budgets der Tool-Module (ihr
Namensraum, etwa `auth_sms.SmsSendBudget`). Alle teilen sich denselben Mechanismus, nie aber
dieselben Schlüssel. Der Orchestrator stellt nur das Zählwerk; Grenzen, Schlüssel und das
Zurücksetzen legt fest, wem die Sache gehört ([ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)).
Im Orchestrator bleiben nur Zähler, die absichtlich über mehrere Tools gelten oder zu keinem Tool
gehören; jeder hat einen eigenen `@Service` mit eigenen Grenzen:

- **`AccountLockoutService`**
  - *Bereich:* `ACCOUNT`
  - *Zählt:* Fehlgeschlagene Anmeldeversuche an einem Konto
  - *Antwort, wenn die Grenze überschritten ist:* `423 Locked` (`ACCOUNT_LOCKED`) bei IDENTIFIED_AUTH. Bei LOOKUP_AUTH steckt die Sperre in der gewöhnlichen Antwort „E-Mail oder Code ungültig“; sonst ließe sich daraus ablesen, ob ein Konto existiert
- **`PersonLockoutService`**
  - *Bereich:* `PERSON`
  - *Zählt:* Fehlgeschlagene Identifizierungsversuche für eine Person (`ident-fsc` rät ein Geheimnis, und ein Treffer übernimmt das Konto). Zählt nur, wo der Versuch überhaupt eine Person benennt: `ident-eid` bestätigt nur die Karte (ADR-18) und findet niemanden; seine PIN-Versuche begrenzt das Versuchsbudget der Journey
  - *Antwort, wenn die Grenze überschritten ist:* immer in der gewöhnlichen Fehlerantwort, nie als eigener Fehler
- **`ChannelCreationThrottleService`**
  - *Bereich:* `BINDING_KEY`
  - *Zählt:* Eröffnete Kanäle je DPoP-Schlüssel (gleitendes Zeitfenster, jeder Versuch zählt)
  - *Antwort, wenn die Grenze überschritten ist:* `429 Too Many Requests`
- **`AdminLoginThrottleFilter`**
  - *Bereich:* `ADMIN`
  - *Zählt:* Falsche Passwörter je Admin-Benutzername
  - *Antwort, wenn die Grenze überschritten ist:* `429 Too Many Requests` (Abschnitt 3c)

Diese vier sind **Sperren** bzw. Sperren-ähnliche Zähler des Orchestrators. Die ersten beiden
gelten für Rateversuche gegen ein Konto oder eine Person, über alle Tools und Kanäle hinweg, und
werden nur durch einen Erfolg zurückgesetzt. Tools, die ihr Subjekt selbst auflösen (Lookup und
Identifizierung), lesen sie über den Port `Lockouts`; schreiben kann sie nur der Orchestrator, aus
dem `Failed`-Ergebnis des Tools.

Etwas anderes sind die **Budgets** der Tool-Module: Sie begrenzen keinen Rateversuch, sondern den
Versand. Die Module zählen über den Port `AttemptBudgets` (`tool_api.budget`) in ihrem eigenen
Namensraum ([Tool-Architektur](03-tool-architektur.md) Abschnitt 4):

- **`SmsSendBudget`** (`auth_sms`) und **`EmailSendBudget`** (`auth_email`)
  - *Zählt:* **Versendete** TANs und Codes je Mobilnummer bzw. je E-Mail-Adresse, über alle Tools
    des Moduls (`enroll-sms`, `auth-sms`, `auth-sms-lookup` bzw. `confirm-email`, `auth-email`,
    `auth-email-lookup`); gleitendes Zeitfenster, 3 in 10 Min. Normalisiert wie der Versand selbst:
    Rufnummer über `PhoneNumber` (Trennzeichen raus, `00` → `+`), sodass `+49-170…` und
    `0049170…` eine Nummer sind; E-Mail klein geschrieben und ohne Leerzeichen am Rand. Der
    Schlüssel steht nur als HMAC-SHA256 mit dem OTP-Pepper in der Tabelle
  - *Zurückgesetzt:* sobald ein Code dieser Nummer oder Adresse richtig eingegeben wurde. Wer die
    Codes angefordert hat, bekommt sie also; wer einen Fremden überschwemmt, kommt nie dahin
  - *Antwort, wenn die Grenze überschritten ist:* beim Einrichten (`enroll-sms`, `confirm-email`:
    Nummer und Adresse hat der Nutzer selbst eingegeben) und bei der Anmeldung mit bekanntem Konto
    `429 Too Many Requests`. Das kostet keinen Versuch der Journey, denn geraten wurde nichts. Bei
    der Anmeldung über die E-Mail-Adresse antwortet das Tool wie bei einer unbekannten Adresse,
    damit sich nicht ablesen lässt, ob ein Konto existiert

- **Warum das zusätzlich zum Versuchsbudget der Journey nötig ist** (`AuthJourney.attemptBudget`,
  [Orchestrierung](04-orchestrierung.md) Abschnitt 7): Es zählt nur innerhalb *einer* Journey. Mit
  einem neuen Kanal kann ein Client jederzeit neu beginnen. `ACCOUNT` und `PERSON` begrenzen deshalb falsche Rateversuche. Die Versandbudgets
  begrenzen zusätzlich das bloße *erneute Versenden*. Das ist nie ein falscher Rateversuch und löst
  deshalb nie `recordFailure` aus. Ohne sie könnte man über `auth-sms-lookup`,
  `auth-email-lookup`, `enroll-sms` und `confirm-email` beliebig viele SMS und E-Mails an fremde
  Empfänger auslösen.
- Fehlschläge beim Einrichten zählen weder bei `AccountLockoutService` noch bei
  `PersonLockoutService` (`ToolJourneyService.chargeThrottles`, `Failed.NothingGuessed -> Unit`):
  Beim Einrichten wird kein vorhandenes Credential erraten. Den Versand *während* des Einrichtens
  begrenzen die Versandbudgets (oben).
- Grenzwerte: bei Fehlversuchen `MAX_FAILURES = 5` und `LOCKOUT_DURATION = 15 Minuten`; beim Eröffnen
  von Kanälen 20 in 5 Minuten; beim Versand 3 in 10 Minuten.
- Ist eine Sperre abgelaufen, beginnt der nächste Fehlversuch wieder bei eins. Sonst hielte ein
  einzelner falscher Versuch je Sperrdauer ein fremdes Konto, eine Person oder den Admin-Zugang
  dauerhaft gesperrt.
- Jeder Zähler wird mit einer einzigen atomaren `UPDATE`-Anweisung erhöht, unter der Zeilensperre,
  die diese Anweisung selbst setzt. Er wird nie erst gelesen und dann geschrieben: Sonst wäre das
  tatsächliche Budget „Grenze × Zahl gleichzeitiger Anfragen“, und die Sperre ließe sich umgehen.
  Fehlt die Zeile für einen Zähler, gilt: erst `UPDATE`; wurde keine Zeile getroffen, die Zeile
  anlegen und das `UPDATE` wiederholen (`AttemptThrottleRowInitializer`, in einer eigenen
  Transaktion).
- Eine erfolgreiche Anmeldung oder Identifizierung setzt den jeweiligen Zähler zurück
  (`recordSuccess`), ein richtig eingegebener Code das Versandbudget seiner Nummer oder Adresse.
  Der Zähler für die Kanaleröffnung wird nie zurückgesetzt; er ist ein reines gleitendes
  Zeitfenster.
- Aufbewahrung: siehe die Tabelle in Abschnitt 3.

## 5) QR-Login (`auth_qr`): Sicherheit des Pairing-Codes

Der Schritt `input` von `confirm-qr-login` nimmt einen `pairingCode` entgegen, den der Nutzer
eingibt oder den ein Deep-Link vorausfüllt ([`CONFIRM_PEER_LOGIN`](journeys/confirm-peer-login.md)):

- **Schutz davor, einen fremden QR-Code zu bestätigen – Code in Gegenrichtung:** Die Freigabe in
  der App meldet den Browser noch nicht an. Sie erzeugt einen sechsstelligen **Bestätigungscode**,
  den nur die App anzeigt und den der Nutzer in den wartenden Browser tippt; erst dann ist der
  Browser angemeldet (`QrLoginBrowserSide`). Ein Angreifer, der dem Opfer seinen eigenen
  Pairing-Code schickt (per Link oder als QR-Bild), bekommt damit nichts: Das Opfer müsste den Code
  in den Browser des Angreifers tippen oder ihn ausdrücklich weitergeben; die App warnt davor.
  Ein Vergleichscode, den beide Seiten nur anzeigen und den man mit dem Auge vergleicht, reicht
  dafür nicht: Den kann der Angreifer einfach mit in seine Nachricht schreiben.
  **Nicht** geschützt ist gegen ein Opfer, das den Bestätigungscode auf Nachfrage selbst herausgibt.
- **Bestätigungscode:** gespeichert nur als Hash, im Klartext genau einmal an die App ausgeliefert.
  Nach der Freigabe hat der Browser zwei Minuten Zeit; nach drei falschen Codes ist die Anfrage
  verbrannt (`EXPIRED`, `countWrongConfirmation`). Das Versuchsbudget der Journey (3) greift
  zusätzlich.
- **Unteilbare Zustandswechsel:** Freigabe, Ablehnung und Abschluss schreiben nur unter einer
  Bedingung (`approveIfPending`/`denyIfPending`: `status = 'PENDING'` und nicht abgelaufen;
  `completeIfConfirmed`: `status = 'APPROVED'`, richtiger Hash, nicht abgelaufen). Wird keine Zeile
  getroffen, war die Anfrage bereits entschieden, abgelaufen oder der Code falsch. So können nie
  zwei Konten gleichzeitig als `resolvingAccountId` eingetragen werden, und ein Code meldet nie zwei
  Browser an.
- **Zufallsgehalt des `pairingCode`:** 8 Zeichen aus einem Alphabet mit wenig Verwechslungsgefahr
  (ähnlich Crockford-Base32, ohne `I`, `L`, `O` und `U`), etwa 40 Bit. Das ist bewusst weniger als
  bei einem reinen API-Token, weil ein Mensch den Code fehlerfrei abschreiben können muss.

**Noch offen:** Weil die Eingabe von Hand ein regulärer Weg ist, bräuchte der Schritt `input` einen
eigenen Zähler für fehlgeschlagene Suchen nach einem `pairingCode`, etwa je IP-Adresse oder ohne
Bezug auf ein Konto. `AttemptThrottle` (Abschnitt 4) hilft hier nicht, weil noch kein Konto bekannt
ist. Das ist derzeit **nicht umgesetzt**.

`QrLoginRequest.expiresAt` (5 Minuten, `QR_LOGIN_TTL`) orientiert sich an den Laufzeiten der
TANs (`enroll-sms`/`auth-sms`). Abgelaufene Zeilen sind beim Lesen wirkungslos, und
`AuthQrRetentionJob` räumt sie auf (Abschnitt 3).

## 6) Datenbankschema: Konventionen

Das Schema liegt in `src/main/resources/db/migration/<modul>/`, ein Ordner je Modul. Die Regeln
stehen in `db/migration/KONVENTIONEN.md` und gelten für jede Tabelle
([12-entscheidungen.md](12-entscheidungen.md) ADR-14/ADR-16). Ein Diagramm der wichtigsten
Tabellen zeigt [02-domaenenmodell.md](02-domaenenmodell.md) Abschnitt 7.

- **Besitz ist im Aufbau verankert:** Jedes Modul hat ein eigenes Datenbankschema, und jede Tabelle
  liegt im Schema ihres Moduls (`account.anchor`, `auth_sms.enrollment`). Die Tabellennamen bleiben
  kurz, weil das Schema den Modulnamen schon enthält.
- **Fremdschlüssel** gibt es nur innerhalb eines Schemas. Bezüge über Modulgrenzen hinweg (z. B.
  `account_id` in Tabellen des Orchestrators) sind Spalten mit Index und werden über die
  Schnittstellen der Module aufgeräumt.
- **Namen:** Dauerhafte Credentials heißen `<modul>.enrollment`, und dieser vollständige Name ist
  `EnrollmentRef.type`. Die Arbeitsdaten eines Tool-Durchlaufs heißen
  `<modul>.<tool-rolle>_tool_session`. Ihr Schlüssel *ist* die `tool_session_id`; die Zeile ist
  also der Teil von `orchestrator.tool_session`, der im Modul liegt. Die Primärschlüsselspalte heißt
  immer `id`, Verweise heißen `<tabelle>_id`. Indizes und Constraints tragen kein Modulpräfix
  (`ux_anchor_value`).
- **Typen:** Zeitpunkte `TIMESTAMP WITH TIME ZONE`, Enum-Werte `VARCHAR(32)`, ACR-Werte
  `VARCHAR(16)`, Tool-IDs, Methoden, Attributtypen und Quellen `VARCHAR(50)`, Hashes `VARCHAR(64)`.
- **Anker:** Jeder Schreibvorgang auf `account.anchor` verlangt ein Mindestniveau nach
  `AnchorRule.acrFloor` (für das erste Binden und das Ersetzen getrennt). `established_acr` hält das
  tatsächlich nachgewiesene, nach ADR-5 begrenzte Niveau fest ([Domänenmodell](02-domaenenmodell.md)
  Abschnitt 6).
- **Konto:** Änderungen werden über `account.account` gesperrt. Der aktuelle Zustand steht in
  eigenen Zeilen; die Historie wird nur ergänzt, nie geändert ([Domänenmodell](02-domaenenmodell.md)
  Abschnitt 6).
- **Aufbewahrung:** Jede Aufräumabfrage ist eine einzige SQL-Anweisung über viele Zeilen und hat
  einen Index auf ihrer Stichtagsspalte.
- **Migrationen:** grundsätzlich eine Datei je Modul unter `db/migration/<modul>/`; `orchestrator`
  hat zusätzlich `V14__node_signing_key.sql` und `V15__event_publication.sql`
  ([ADR-16](adr/ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md)). Die Migrationen sind eine Ausgangsbasis
  ohne Produktivdaten. **Nur im Demomodus** gilt: Passt eine lokale H2-Datei nicht mehr zu den
  Migrationen, löscht `orchestrator.schema.FlywayResetConfig` sie beim Start und baut sie neu auf;
  `rm -rf data/` von Hand ist nicht nötig. Außerhalb des Demomodus gibt es die Klasse gar nicht,
  und Flyway bricht den Start ab, wie es soll: Die H2-Datei ist dann die Betriebsdatenbank, und ein
  Migrationsfehler darf nie Konten und Änderungsprotokoll löschen. Die Demo-Personen
  (`demo_seed`) werden außerhalb des Demomodus nicht migriert. Ab dem ersten produktiven Einsatz sind Migrationen nur noch additiv, und Tabellen
  mit 10 Millionen Zeilen oder mehr werden in wiederholbaren Portionen umgestellt.

## 7) Zustand und Kennzahlen (Actuator)

Health und Kennzahlen liegen auf einem **eigenen Management-Port** (`MANAGEMENT_PORT`, Standard
`9080`), den kein Service und keine Route nach außen führt; auf dem öffentlichen Port `8080` gibt es
`/actuator` nicht. Freigegeben sind nur zwei Endpunkte:

- **`/actuator/health`** mit den Probes `/actuator/health/liveness` und `/actuator/health/readiness`.
  Die OpenShift-Probes nutzen sie. `readiness` wird erst nach allen Startschritten bereit, also nach
  den Keycloak-Migrationen (dasselbe Fenster, in dem `ReadinessGateFilter` die API mit `503`
  beantwortet), und hängt an der Datenbank.
  - **Keycloak** steht als eigene Komponente `keycloak` in `/actuator/health`, aber nicht in
    `readiness`: Der App-Kanal braucht Keycloak nicht. Fiele jede Instanz bei einem
    Keycloak-Ausfall aus dem Lastverteiler, würde aus einem Teilausfall ein Totalausfall. Die
    Komponente ist für den Alarm da, nicht für die Verteilung.
- **`/actuator/prometheus`** mit den Kennzahlen:
  - **`identity.throttle.blocked`** (je `scope`): wie oft Sperre oder Mengenbegrenzung eine Anfrage
    abgewiesen hat (Abschnitt 4). Ein Anstieg ist ein Angriff oder ein Fehler.
  - **`identity.retention.deleted`** (je `table`): gelöschte Zeilen der Aufräumläufe (Abschnitt 3). Eine
    flache Linie über Tage heißt: Ein Lauf löscht nicht mehr.
  - **`identity.events.incomplete`**: noch nicht abgeschlossene Event-Publikationen
    (`orchestrator.event_publication`). Einige sind normal; eine Zahl, die nur wächst, heißt: Ein
    Listener scheitert an jedem Ereignis, z. B. das Aufräumen in Keycloak nach einer Kontolöschung.
  - **`http.client.requests`** (je `client.name`): Dauer und Ergebnis jedes Aufrufs an Keycloak.

Im Demomodus zeigt die Willkommensseite unter „Server-Status“ denselben Zustand und dieselben
Kennzahlen (`GET /orchestrator/demo/server-info`, Block `operations`); der Browser erreicht den
Management-Port nicht, deshalb liest das Backend sie aus.

**Logs.** Jede Zeile, die während einer Anfrage geschrieben wird, trägt eine Anfrage-Id und – wenn
der Pfad sie nennt – die `channelSessionId` bzw. `toolSessionId` (`LoggingContextFilter`, MDC).
Nur Ids aus der URL, nichts Persönliches. Lokal stehen sie in eckigen Klammern vor der Meldung; im
Betrieb schreibt der Orchestrator strukturiert als ECS-JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`,
in `openshift/identity-demo.yaml` gesetzt), mit den Ids als eigenen Feldern.
