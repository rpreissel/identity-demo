# Stand und Weg zur Produktion

Dieses Kapitel beantwortet fünf Fragen:

- Was soll das Projekt leisten?
- Welche Teile sind bereit für den echten Betrieb (produktionsreif)?
- Welche Teile dienen nur der Vorführung?
- Was fehlt noch, bevor eine Instanz mit echten Personendaten laufen darf?
- Was müsste sich bei einem großen Mengengerüst ändern (Abschnitt 7)?

Das Kapitel fasst nur zusammen. Die Einzelheiten stehen in den verlinkten Kapiteln und
Entscheidungen.

> **Kurz:** Der Backend-Kern ist als produktionsreif gedacht und wird entsprechend geprüft. Die
> Fremdsysteme sind simuliert. Frontends und Ausführungsumgebung sind nur für die Vorführung gebaut.
> Solange sie nicht gegen Angriffe abgesichert sind, läuft keine Instanz mit echten Personendaten
> ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)).

---

## 1) Was das Projekt leisten soll

Versicherte einer Krankenkasse registrieren sich, identifizieren sich und melden sich an. Das geht
in einer **App** und auf einer **Website**, und beide nutzen dieselben Konten. Am Ende steht immer
ein `AccessToken`. Das ist ein signierter digitaler Ausweis, mit dem App oder Website die
Fachdienste der Versicherung direkt aufrufen ([01-ueberblick.md](01-ueberblick.md), Abschnitt 1).

Das Projekt soll zeigen, dass dieser Ansatz funktioniert. Er beruht auf vier Ideen:

- **Regeln im Backend:** Welcher Schritt als Nächstes kommt, entscheidet der Orchestrator, also
  der Server dieses Projekts. App und Website zeigen nur an, was er vorgibt. Für eine neue Regel
  braucht es deshalb kein App-Update ([04-orchestrierung.md](04-orchestrierung.md)).
- **Das Gerät als Schlüssel:** Jede Anfrage der App ist an den Schlüssel des Geräts gebunden. Das
  geschieht mit DPoP, einem Standard, bei dem die App jede Anfrage mit ihrem Schlüssel unterschreibt
  ([09-dpop.md](09-dpop.md)).
- **Keycloak ohne Kopie der Konten:** Auf der Website übernimmt ein unverändertes Keycloak die
  Anmeldung. Keycloak ist das Produkt, das die Tokens ausstellt. Es liest die Konten beim
  Orchestrator nach, statt sie zu kopieren ([ADR-38](adr/ADR-038-keycloak-liest-konten.md)).
- **Niveaus mit Nachweis:** Ein Sicherheitsniveau sagt, wie sehr das System einer Anmeldung
  vertraut. Es wird nur vergeben, wenn ein Verfahren es belegt
  ([ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md)). Der Journey-Trace, die Aufzeichnung aller
  Schritte, zeigt, welcher Schritt was entschieden hat.

Den Beweis dafür liefert ein **produktionsreifer Backend-Kern**, nicht eine Oberfläche
([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)). Ob der Ansatz
funktioniert, zeigt sich an vier Dingen: an der Steuerung der Abläufe (Orchestrierung), an den
Niveaus, an der Bindung an Konten und an der DPoP-Bindung. Eine abgesicherte Oberfläche oder eine
abgesicherte Installation sagt darüber nichts aus.

---

## 2) Drei Bereiche mit verschiedenem Anspruch

Nicht jeder Teil des Repositorys muss dieselbe Qualität haben. Die Tabelle teilt es in drei
Bereiche.

| Bereich | Was dazugehört | Anspruch |
|---|---|---|
| **Kern** | `core/` (Orchestrator samt Keycloak-Anbindung, Konto), `contract/`, `tools/` (alle Verfahren `auth_*`, `ident_*`), die Keycloak-Extension und die Realm-Migrationen | produktionsreif |
| **Simulierte Fremdsysteme** | `simulation/` (Personenverzeichnis, KOBIL, Nect, SMS, Mail), `demo/demo_seed`, die simulierte eID-Kartenlesung | nur für die Vorführung, hinter Ports |
| **Frontends und Umgebung** | `frontend/`, `keycloak-theme/`, `compose.yml`, `Dockerfile`, `openshift/`, Betriebskonfiguration | vorführfähig, wird später abgesichert |

Zwischen Kern und Simulation liegt ein **Port**. Ein Port ist eine fest vereinbarte Schnittstelle,
über die der Kern ein Fremdsystem anspricht. Der Kern vertraut einem Fremdsystem nur in dem, was der
Port-Vertrag zusagt. Ein ArchUnit-Test stellt sicher, dass der Kern kein Mock-Modul direkt anspricht
([08-projektrahmen.md](08-projektrahmen.md), „Simulierte Fremdsysteme“). Was ein echtes System
zusätzlich leisten muss, steht im Vertrag, nicht im Mock ([port-vertraege.md](port-vertraege.md)).

---

## 3) Was produktionsreif ist

An den Kern gibt es zwei Anforderungen. Erstens gilt jede Sicherheitszusage, ohne dass sie sich auf
eine unausgesprochene Annahme über die Umgebung stützt. Zweitens werden feste Regeln (Invarianten)
durch einen Typ, eine Datenbankregel (Constraint) oder einen Test erzwungen, nicht nur durch einen
Kommentar. Diese Punkte belegen das:

- **Invarianten mit Mechanismus:** Jede Regel, auf die sich der Kern verlässt, nennt den
  Mechanismus, der sie sichert (`test:`, `archunit:`, `type:`, `sql:`). Ein Test prüft, dass es
  diesen Mechanismus gibt ([invarianten.md](invarianten.md)).
- **Niveaus nur mit Nachweis:** Manche Verfahren vergeben ein höheres Niveau, als sie wirklich
  beweisen können. Diese Verfahren sind als `demoOnly` gekennzeichnet und außerhalb des Demomodus
  abgeschaltet. Ein Test beweist das für jedes dieser Verfahren
  ([ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md)).
- **DPoP-Bindung:** feste Header, nur EC-Schlüssel, Schutz gegen wiederholt eingespielte Anfragen
  (Replay-Schutz) über die Datenbank und Abgleich der Adresse nach RFC 9449
  ([09-dpop.md](09-dpop.md); [07-betrieb.md](07-betrieb.md), Abschnitt 3c).
- **Fehlervertrag, Transaktionen, Aufbewahrung und Löschung:** [07-betrieb.md](07-betrieb.md),
  Abschnitte 1 bis 3. Die Aufbewahrungsfristen sind Richtwerte, keine Compliance-Vorgabe.
- **Keycloak-Anbindung:** Keycloak liest die Konten nach (Föderation), statt sie zu spiegeln. Nach
  einer Löschung räumt der Orchestrator in Keycloak auf. Dafür nutzt er die Event Publication
  Registry ([ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md)).
- **Sperren, Mengenbegrenzung, Zustand und Kennzahlen:** ein eigener Management-Port mit Health und
  Readiness, Kennzahlen für Prometheus und strukturierte Logs
  ([07-betrieb.md](07-betrieb.md), Abschnitte 4 und 7).
- **Start verweigert unsichere Konfiguration:** Außerhalb des Demomodus bricht der Start ab, wenn
  Admin-Passwort, Pepper, Geheimnisse oder TLS zu Keycloak nicht stimmen
  ([07-betrieb.md](07-betrieb.md), Abschnitt 3c).
- **Prüfungen im Build:** In der CI laufen diese Prüfungen: Modulgrenzen, Architekturregeln,
  OpenAPI-Snapshot und Kompatibilität der veröffentlichten API, Tests, Lint, `npm audit`, CodeQL und
  Playwright ([08-projektrahmen.md](08-projektrahmen.md), Abschnitt 7).
- **Unabhängige Bewertung:** Es gab drei Bewertungen und ein Sicherheitsaudit (2026-10-03, mit zwei
  hohen Befunden). Alle hohen und mittleren Befunde sind bearbeitet. Was noch offen ist, steht in
  [offene-befunde.md](offene-befunde.md).

---

## 4) Was Demo ist

### Simulierte Fremdsysteme

Alle Fremdsysteme sind in dieser Instanz simuliert. Die Simulationen sind nicht so sicher wie ein
echtes System. Was ein echtes System zusagen muss, steht in [port-vertraege.md](port-vertraege.md).

| System | Heute | Was ein echtes System zusagen muss | `demoOnly` |
|---|---|---|---|
| **Personenverzeichnis** | simuliert, mit ungeschützter Verwaltungs-API | [Port-Vertrag](port-vertraege.md#personenverzeichnis-persondirectory-personmasterdata-activationcodes-invitations) | nein – der wichtigste Vertrag |
| **KOBIL** | simuliert; PIN und Aktivierungsgeheimnis im Klartext ([ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)) | [Port-Vertrag](port-vertraege.md#kobil-kobilkobilssms--demoonly) | ja |
| **Nect** | simuliert | [Port-Vertrag](port-vertraege.md#nect-nectnectident--demoonly) | ja |
| **eID-Server** | simulierte Kartenlesung | [Port-Vertrag](port-vertraege.md#eid-server-in-ident_eid-simuliert--demoonly) | ja |
| **SMS und E-Mail** | Nachrichten werden in einem simulierten „Briefkasten“ abgelegt | [Port-Vertrag](port-vertraege.md#zustellung-von-tan-und-code-sms-mail) | nein – der Kern prüft den Code selbst |

Auch der **Gerätefaktor** ist `demoOnly`, obwohl er kein Fremdsystem ist. Der Grund: Dass der Nutzer
auf dem Gerät geprüft wurde, behauptet nur ein JWT, das die App selbst signiert.

### Der Demomodus

Der Demomodus ist ein Schalter: `demo.mode`, als Umgebungsvariable `DEMO_MODE`. Er ist
standardmäßig an. Nur im Demomodus gibt es:

- die Demo-Oberflächen (Briefkasten, Mock-Postausgänge, Zurücksetzen),
- die `demoOnly`-Verfahren,
- Demo-Werte in den Antworten ([ADR-28](adr/ADR-028-demo-werte-abschaltbar.md)),
- die Demo-Personen,
- das Zurücksetzen der Datenbank ([07-betrieb.md](07-betrieb.md), Abschnitt 8).

### Frontends und Umgebung

- **Browser als Gerät:** Die Schlüssel liegen in IndexedDB, dem Speicher des Browsers, nicht in
  einem Secure Element. Ein Produktivsystem bräuchte eine native App mit einem Schlüsselspeicher in
  der Hardware des Geräts ([09-dpop.md](09-dpop.md)).
- **Ausführung:** Es gibt `compose.yml` mit einem selbstsignierten Zertifikat und einen Prototyp
  für OpenShift ([13-ausfuehren.md](13-ausfuehren.md)). Beides ist nur für die Vorführung gedacht.
- **Datenbank:** eine H2-Datei ([08-projektrahmen.md](08-projektrahmen.md), Abschnitt 4).

---

## 5) Was vor echten Personendaten fehlt

Die Liste ist nach Bereichen geordnet. Die Kennungen `DPoP-demo-…` sind Issues im Tracker. Sie
lassen sich mit `bd show` anzeigen.

**Umgebung und Frontend** (ADR-35, Bereich 3):

- TLS zwischen Keycloak und Orchestrator, Proxy-Header, Compose-Ports (`DPoP-demo-ai4x`).
- Keycloak im optimierten Startmodus mit festem Hostnamen, DB-Passwort per Secret
  (`DPoP-demo-9msv`), Admin-Geheimnis auf OpenShift (`DPoP-demo-x25a`).
- CSP im Frontend, Tokens des Web-Kanals, Thumbprint nach RFC 7638 (`DPoP-demo-dm2j`).

**Schlüssel und Daten:**

- Ein Zielbild für die Verwaltung der Schlüssel: KMS oder HSM, Rotation, Widerruf
  (`DPoP-demo-61kp`). Heute liegen die Schlüssel in derselben Datenbank
  ([ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)).
- Spalten mit personenbezogenen Daten sind nicht verschlüsselt
  ([Idee Umschlagverschlüsselung](ideen/verschluesselung-differenzierte-aufbewahrung.md),
  `DPoP-demo-bo1w`).
- Die Aufbewahrungsfristen müssen mit Datenschutz und Compliance festgelegt werden. Heute sind es
  Richtwerte.

**Betrieb:**

- PostgreSQL statt H2 (`DPoP-demo-pi55`). Ab dem ersten produktiven Einsatz dürfen Migrationen nur
  noch etwas hinzufügen, nichts mehr ändern oder entfernen.
- Sicherung und Wiederherstellung beschreiben und üben (`DPoP-demo-prnl`).
- Mehr als eine Instanz: Dafür braucht es eine gemeinsame Sperre für geplante Aufgaben und einen
  festen Pepper (`DPoP-demo-g7np`). Die Replay-Tabelle für DPoP skaliert in dieser Form nicht
  ([offene-befunde.md](offene-befunde.md) Abschnitt 8, „Erkannte, bewusst zurückgestellte
  Verbesserungen“).
- Dashboards und Alarme. Heute gibt es Kennzahlen, aber keine Auswertung.

**Echte Fremdsysteme:** Jede Anbindung muss ihren Port-Vertrag erfüllen. Mit Nect ist begonnen
(`DPoP-demo-v033`, `DPoP-demo-z90h`). Das Personenverzeichnis ist der wichtigste Vertrag. Denn
außerhalb des Demomodus ist es der einzige Weg zu einer Identifizierung.

**Offene Befunde im Kern:** Sie sind in [offene-befunde.md](offene-befunde.md) gesammelt, dort
stehen auch die Restrisiken. Ein Punkt muss vor einer produktiven Passwortanmeldung per Lookup
erledigt sein: Die Sperre für Konto und Person muss einen Versuch vor der Prüfung zählen, nicht erst
danach ([07-betrieb.md](07-betrieb.md) Abschnitt 4, „Restrisiko: parallele Versuche“,
`DPoP-demo-164n.29`).

---

## 6) Wie es weitergeht

Hier folgt ein Vorschlag für die Reihenfolge. Er ist aus ADR-35 und den
[offenen Befunden](offene-befunde.md) abgeleitet und noch nicht entschieden.

Am wichtigsten sind die ersten beiden Schritte. Die Doku beschreibt ein Zielbild, das bisher nur im
Projekt selbst entstanden ist. Bevor das System weiter abgesichert und an echte Systeme angebunden
wird, müssen die Menschen zustimmen, die es später fachlich und technisch verantworten sollen.

1. **Mit Fachexperten Domäne, Regeln und Journeys abstimmen.** Zu prüfen ist:
   - Stimmen Begriffe, Konten und Zustände ([Domänenmodell](02-domaenenmodell.md),
     [Glossar](glossar/glossar.md))?
   - Stimmen die Regeln für Sicherheitsniveaus und Verfahren
     ([Orchestrierung und Policy](04-orchestrierung.md))?
   - Stimmen die Abläufe je Intent ([Journeys](journeys/))?

   Was hier abweicht, ändert das Modell. Jetzt ist eine solche Änderung noch günstig.
2. **Mit Entwicklern Architektur, Konzepte und fehlende Anforderungen abstimmen.** Zu prüfen ist:
   - Sind Modulschnitt, Tool-Architektur, API und DPoP-Bindung geeignet
     ([Projektrahmen](08-projektrahmen.md), [Tool-Architektur](03-tool-architektur.md),
     [Architekturentscheidungen](12-entscheidungen.md))?
   - Welche Anforderungen fehlen noch, etwa an Schnittstellen zu Fachdiensten, Mandanten, Last oder
     Barrierefreiheit? Was ein großes Mengengerüst an der Architektur ändern würde,
     steht in Abschnitt 7.

   Offene Konzepte stehen unter [Ideen](ideen/).
3. **Den Kern abschließen.** Dazu gehören die [offenen Befunde](offene-befunde.md), zuerst die zu
   Sicherheit und Keycloak (Abschnitte 1 und 2), und die offenen Invarianten. Der Kern ist der
   Beweis für den Ansatz. Deshalb muss er jede genaue Prüfung bestehen.
4. **Grundsatzentscheidungen treffen.** Das betrifft die Verwaltung der Schlüssel
   (`DPoP-demo-61kp`), die Verschlüsselung gespeicherter Daten (`DPoP-demo-bo1w`), die
   Zieldatenbank (`DPoP-demo-pi55`) und die Frage, ob mehr als eine Instanz laufen soll. Diese
   Entscheidungen betreffen Modell und Migrationen. Je später sie getroffen werden, desto teurer
   werden sie.
5. **Umgebung absichern** (ADR-35, Bereich 3): TLS, Startmodus von Keycloak, Proxy,
   Admin-Zugänge, Sicherung. Das Ziel ist eine Umgebung, in der das System mit `demo.mode=false`
   startet und dauerhaft läuft.
6. **Echte Fremdsysteme anbinden**, eines nach dem anderen und jeweils gegen seinen Port-Vertrag:
   zuerst das Personenverzeichnis, dann die Identifizierung (Nect, eID), dann KOBIL. Ein Verfahren
   verliert die Kennzeichnung `demoOnly` erst, wenn sein echtes System den Nachweis liefert.
7. **Die App als native App.** Das Frontend im Browser zeigt die Abläufe. Ein produktives Gerät
   braucht aber einen Schlüsselspeicher in der Hardware.
8. **Freigabe für echte Personendaten.** Die Einschränkung aus ADR-35 endet erst, wenn die Schritte
   1 bis 7 erledigt sind und Datenschutz, Sicherheit und Betrieb zugestimmt haben.

---

## 7) Was ein großes Mengengerüst verlangt

Dieser Abschnitt prüft die Architektur gegen ein angenommenes Mengengerüst: 20 Millionen Konten,
15 Millionen Versicherte im Personenverzeichnis und 1 Million Anmeldungen am Tag. Im Mittel sind
das etwa 12 Anmeldungen pro Sekunde. Für Spitzen sind hier 100 bis 300 Anmeldungen pro Sekunde
angenommen. Verbindliche Lastanforderungen gibt es noch
nicht (Abschnitt 6, Schritt 2). Die Zahlen unten sind deshalb Schätzungen. Stand der Prüfung:
2026-10-05.

**Kurz:** Das Datenmodell trägt diese Größe. Die häufigen Abfragen lesen einzelne Zeilen über
Indizes (siehe [offene-befunde.md](offene-befunde.md) Abschnitt 8, „Sehr viele Konten“). Was nicht
trägt, ist der Betrieb: Das System läuft heute als genau eine Instanz. Dazu kommen einige Stellen,
die alles auf einmal lesen oder löschen oder andere Systeme öfter aufrufen als nötig.

### Was für mehr als eine Instanz fehlt

Der Orchestrator verweigert den Start mit `deployment.instances=multiple` und nennt dabei, was fehlt
(`DeploymentTopology`, [07-betrieb.md](07-betrieb.md) Abschnitt 3b). Im Einzelnen:

- **Datenbank.** Heute ist es eine H2-Datei, die nur ein Prozess öffnen kann. Nötig ist PostgreSQL
  (`DPoP-demo-pi55`). Einige Flyway-Migrationen nutzen Besonderheiten von H2 und müssen dafür
  angepasst werden.
- **Schlüssel für `restoreData`.** `RestoreDataCodec` erzeugt seinen Schlüssel bei jedem Start neu.
  Eine Web-Anmeldung, die auf einer Instanz beginnt, kann eine andere Instanz deshalb nicht
  fortsetzen. Nötig ist ein gemeinsamer Schlüssel.
- **Keycloak-Migrationen beim Start.** Sie laufen ohne Sperre. Starten zwei Instanzen gleichzeitig,
  wenden beide dieselben Schritte an. Scheitert eine, rollt sie auch die Schritte der anderen
  zurück. Nötig ist eine Sperre oder ein eigener Schritt beim Ausrollen.
- **Geplante Aufgaben.** Fünf Aufgaben (Aufräumen, Replay-Tabelle, Änderungsprotokoll,
  Anmeldeprotokoll, Tool-Sitzungen) laufen ohne gemeinsame Sperre, etwa ShedLock
  (`DPoP-demo-g7np`).
- **Pepper für Einmalcodes.** Ist er nicht gesetzt, wählt jede Instanz einen eigenen. Außerhalb des
  Demomodus muss er ohnehin fest gesetzt sein.
- **Reihenfolge der Änderungen aus dem Personenverzeichnis.** Die Reihenfolge je Person sichert
  heute ein einzelner Thread im Prozess (ADR-34). Über mehrere Instanzen gilt das nicht mehr.

### Engpässe unter Last

1. **Jede Erneuerung eines Tokens liest live beim Personenverzeichnis.** Keycloak merkt sich ein
   gelesenes Konto höchstens 60 Sekunden (Migration V2). Ein AccessToken gilt aber 300 Sekunden
   (V5). Praktisch jede Erneuerung liest das Konto deshalb neu beim Orchestrator, und der liest die
   Stammdaten dabei live beim Personenverzeichnis. Bei 20.000 bis 400.000 gleichzeitigen Sitzungen
   sind das geschätzt 70 bis 1.400 Abfragen pro Sekunde. Abhilfe: den Cache an die Laufzeit des
   Tokens anpassen oder die Stammdaten zwischenspeichern.
2. **Der Abruf des Tokens bei Keycloak läuft in einer offenen Datenbanktransaktion.** Im App-Kanal
   holt der Orchestrator das Token, während seine Transaktion offen ist. Keycloak ruft dabei den
   Orchestrator zurück, um das Konto zu lesen. Mit einem Lese-Timeout von 10 Sekunden hält ein
   langsames Keycloak so lange einen Thread und eine Datenbankverbindung fest. Dazu kommt:
   - Datenbank-Pool und Tomcat laufen mit den Voreinstellungen (10 Verbindungen).
   - Virtuelle Threads sind nicht eingeschaltet.
   - Es gibt keinen Circuit Breaker, der Aufrufe an ein ausgefallenes System abbricht.
3. **Replay-Tabelle.** Jede Anfrage der App und jeder Aufruf von Keycloak an den Orchestrator
   schreibt eine Zeile in `dpop_proof_replay`, jeweils in einer eigenen Transaktion. Eine
   Web-Anmeldung erzeugt etwa sechs solcher Zeilen. Jede Minute läuft dazu eine Löschung. Der Punkt
   ist bereits als offen erfasst ([offene-befunde.md](offene-befunde.md) Abschnitt 8).
4. **Das stündliche Aufräumen läuft in einer einzigen Transaktion.** `RetentionJob.cleanup` löscht
   darin unter anderem die abgelaufenen Einträge des Journey-Trace in einer einzigen Anweisung.
   Geschätzt sind das 170.000 bis 330.000 Zeilen pro Stunde. Nötig sind kleine Portionen mit
   eigener Transaktion oder Partitionen nach Zeit.
5. **Stündlicher Durchlauf über alle Konten.** Die Suche nach abgebrochenen Registrierungen filtert
   und sortiert nach `account.created_at`. Diese Spalte hat keinen Index. Bei 20 Millionen Konten
   liest die Abfrage jede Stunde die ganze Tabelle. Danach folgt je gefundenem Konto eine weitere
   Abfrage.
6. **Fehlender Index beim Abmelden.** Bei jeder Abmeldung in Keycloak sucht der Orchestrator über
   `app_token_session.keycloak_session_id`. Diese Spalte hat keinen Index. Weitere Spalten ohne
   Index sind `channel_session.invitation` und `change_log.lookup_key_id`. Über die letzte liest der
   Orchestrator bei jedem Start die ganze Tabelle.
7. **Admin-Listen laden alle Konten.** Die Kontoliste zum Journey-Trace
   (`AdminJourneyTraceController`) lädt alle Konto-Ids und danach für jedes Konto den Namen beim
   Personenverzeichnis. Sie ist nicht auf den Demomodus beschränkt. Im Demomodus tun
   `AdminAccountsController`, `KeycloakRealmSessions` und `DemoReset` dasselbe. Nötig ist eine
   Suche oder seitenweises Laden.
8. **Massenänderungen im Personenverzeichnis.** Ändern sich viele Personen auf einmal, etwa
   Millionen zum Jahreswechsel, arbeitet ein einziger Thread die Ereignisse ab. Seine Warteschlange
   hat keine Grenze, und jedes Ereignis schreibt mehrmals. Durchsatz und Reihenfolge über mehrere
   Instanzen sind ungeklärt.
9. **`/idclaims` liest ohne Zwischenspeicher.** Jeder Aufruf liest Mitgliedsnummer und Namen live
   beim Personenverzeichnis.
10. **SMS und E-Mail.** Die Tools versenden synchron und ohne Port, ohne Outbox und ohne
    Wiederholung. Sie nutzen die Simulation direkt.
11. **Kleinere Punkte.** Der Orchestrator prüft die Signatur jedes Aufrufs von Keycloak zweimal.
    Der Zwischenspeicher für die Schlüssel von Keycloak (JWKS) sperrt global und lädt unter dieser
    Sperre mit 10 Sekunden Timeout nach.

### Wie die Daten wachsen

Eine Anmeldung schreibt geschätzt 12 bis 20 Zeilen. In der Spitze sind das 1.500 bis 2.500 Zeilen
pro Sekunde. Bei den heutigen Aufbewahrungsfristen entstehen ungefähr diese Bestände:

| Tabelle | Bestand (Schätzung) | Hinweis |
|---|---|---|
| `account.sign_in_log` | etwa 365 Millionen Zeilen (6 Monate) | heute in Portionen zu 500 Zeilen gelöscht, also etwa 4.000 Transaktionen am Tag |
| `orchestrator.journey_trace` | 56 bis 112 Millionen Zeilen (14 Tage) | stündlich in einer Anweisung gelöscht, siehe Punkt 4 |
| `orchestrator.channel_session` | etwa 14 Millionen Zeilen (14 Tage) | |
| `account.claim` | 20 bis 80 Millionen Zeilen | siehe [offene-befunde.md](offene-befunde.md) Abschnitt 8 |
| `event_publication` | wächst ohne Grenze | abgeschlossene Einträge räumt niemand ab |

Für `sign_in_log` und `journey_trace` passen Partitionen nach Zeit besser als Löschen: Eine
abgelaufene Partition wird als Ganzes entfernt.

### Was schon trägt

- Konto, Anker, E-Mail-Adresse, Partnernummer, Geräteverknüpfung und Kanal werden über Indizes
  einzeln gelesen.
- Sitzungen, Journeys, Zähler für Sperren und Mengenbegrenzungen, QR-Anfragen und die
  Signaturschlüssel des Orchestrators liegen in der Datenbank. Sie würden also auch über mehrere
  Instanzen hinweg funktionieren.
- Keycloak ruft den Orchestrator nicht bei jeder Anmeldung auf, sondern nur bei einer Abmeldung. Es
  fragt auch nicht regelmäßig nach.
- Eine Suche über die Nutzer-Federation liefert nie eine Liste, höchstens einen Treffer.
- Die Sperre auf der Konto-Zeile ist optimistisch und greift nur bei Änderungen am Konto, nicht bei
  der Anmeldung.

### Was hier nicht geprüft ist

- Keycloak selbst muss als Cluster mit eigener Datenbank laufen. Seine Sitzungsspeicher müssen für
  bis zu etwa 400.000 gleichzeitige Sitzungen ausgelegt sein. Die Sitzungen der App sind dauerhaft
  gespeichert (`PERSISTENT`).
- Für die echten Anbindungen an Personenverzeichnis, Nect, KOBIL, SMS und E-Mail braucht es
  Timeouts und Circuit Breaker. Die [Port-Verträge](port-vertraege.md) nennen bisher keine
  Anforderungen an Antwortzeit und Verfügbarkeit.
- Einen Lasttest gibt es noch nicht.

### Vorgeschlagene Reihenfolge

1. Die Lastanforderungen festlegen (Abschnitt 6, Schritt 2).
2. PostgreSQL und die Punkte unter „Was für mehr als eine Instanz fehlt“.
3. Den Weg der Token-Erneuerung entlasten (Punkt 1) und den Aufruf von Keycloak aus der
   Transaktion lösen (Punkt 2).
4. Das Aufräumen in Portionen oder Partitionen umbauen und die fehlenden Indizes anlegen
   (Punkte 4 bis 6).
5. Die Admin-Listen auf Suche oder seitenweises Laden umstellen (Punkt 7).
6. Einen Lasttest aufsetzen.
