# Stand und Weg zur Produktion

Dieses Kapitel sagt, was das Projekt leisten soll, welche Teile produktionsreif sind, welche nur
vorgeführt werden und was fehlt, bevor eine Instanz mit echten Personendaten laufen darf. Es fasst
zusammen und verweist; die Einzelheiten stehen in den verlinkten Kapiteln und Entscheidungen.

> **Kurz:** Der Backend-Kern ist produktionsreif gemeint und so geprüft. Die Fremdsysteme sind
> simuliert, Frontends und Ausführungsumgebung sind Vorführrahmen. Bis diese gehärtet sind, läuft
> keine Instanz mit echten Personendaten ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)).

---

## 1) Was das Projekt leisten soll

Versicherte einer Krankenkasse registrieren, identifizieren und melden sich in einer **App** und auf
einer **Website** an; beide nutzen dieselben Konten. Am Ende steht immer ein `AccessToken`, mit dem
App oder Website die Fachdienste der Versicherung direkt aufrufen
([01-ueberblick.md](01-ueberblick.md), Abschnitt 1).

Das Projekt soll zeigen, dass dieser Ansatz trägt:

- **Regeln im Backend:** Welcher Schritt als Nächstes kommt, entscheidet der Orchestrator. App und
  Website zeigen nur an, was er vorgibt; eine neue Regel braucht kein App-Update
  ([04-orchestrierung.md](04-orchestrierung.md)).
- **Das Gerät als Schlüssel:** Jede Anfrage der App ist per DPoP an den Schlüssel des Geräts
  gebunden ([09-dpop.md](09-dpop.md)).
- **Keycloak ohne Kopie der Konten:** Auf der Website meldet ein Standard-Keycloak an und liest die
  Konten beim Orchestrator ([ADR-38](adr/ADR-038-keycloak-liest-konten.md)).
- **Niveaus mit Nachweis:** Ein Sicherheitsniveau wird nur vergeben, wenn ein Verfahren es belegt
  ([ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md)); der Journey-Trace zeigt, welcher Schritt
  was entschieden hat.

Den Beleg liefert ein **produktionsreifer Backend-Kern**, nicht eine Oberfläche
([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)). Ob der Ansatz trägt,
entscheidet sich an Orchestrierung, Niveaus, Kontobindung und DPoP-Bindung; eine gehärtete
Oberfläche oder ein gehärtetes Deployment sagt darüber nichts aus.

---

## 2) Drei Bereiche mit verschiedenem Anspruch

| Bereich | Was dazugehört | Anspruch |
|---|---|---|
| **Kern** | `core/` (Orchestrator samt Keycloak-Anbindung, Konto), `contract/`, `tools/` (alle Verfahren `auth_*`, `ident_*`), die Keycloak-Extension und die Realm-Migrationen | produktionsreif |
| **Simulierte Fremdsysteme** | `simulation/` (Personenverzeichnis, KOBIL, Nect, SMS, Mail), `demo/demo_seed`, die simulierte eID-Kartenlesung | Vorführrahmen hinter Ports |
| **Frontends und Umgebung** | `frontend/`, `keycloak-theme/`, `compose.yml`, `Dockerfile`, `openshift/`, Betriebskonfiguration | vorführfähig, wird später gehärtet |

Die Grenze zwischen Kern und Simulation ist ein **Port**: Der Kern vertraut einem Fremdsystem nur mit
dem, was der Port-Vertrag zusagt, und ein ArchUnit-Test sichert, dass er kein Mock-Modul direkt
anspricht ([08-projektrahmen.md](08-projektrahmen.md), „Simulierte Fremdsysteme“). Was ein echtes
System zusätzlich leisten muss, steht im Vertrag, nicht im Mock ([port-vertraege.md](port-vertraege.md)).

---

## 3) Was produktionsreif ist

Der Anspruch an den Kern: Jede Sicherheitszusage gilt ohne unbenannte Annahme an die Umgebung, und
Invarianten sind per Typ, Constraint oder Test erzwungen, nicht per Kommentar. Belegt ist das so:

- **Invarianten mit Mechanismus:** Jede Regel, auf die sich der Kern verlässt, nennt ihren
  Mechanismus (`test:`, `archunit:`, `type:`, `sql:`); ein Test prüft, dass es ihn gibt
  ([invarianten.md](invarianten.md)).
- **Niveaus nur mit Nachweis:** Ein Verfahren, das mehr vergibt, als es beweisen kann, ist als
  `demoOnly` gekennzeichnet und außerhalb des Demomodus aus; ein Test beweist das für jedes dieser
  Verfahren ([ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md)).
- **DPoP-Bindung:** feste Header, nur EC-Schlüssel, Replay-Schutz per Datenbank, Abgleich
  der Adresse nach RFC 9449 ([09-dpop.md](09-dpop.md); [07-betrieb.md](07-betrieb.md), Abschnitt 3c).
- **Fehlervertrag, Transaktionen, Aufbewahrung und Löschung:** [07-betrieb.md](07-betrieb.md),
  Abschnitte 1 bis 3. Die Aufbewahrungsfristen sind Richtwerte, keine Compliance-Vorgabe.
- **Keycloak-Anbindung:** Föderation ohne Spiegelung; Aufräumen nach einer Löschung über die Event
  Publication Registry ([ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md)).
- **Sperren, Mengenbegrenzung, Zustand und Kennzahlen:** eigener Management-Port mit Health und Readiness,
  Prometheus-Kennzahlen, strukturierte Logs ([07-betrieb.md](07-betrieb.md), Abschnitte 4 und 7).
- **Start verweigert unsichere Konfiguration:** Außerhalb des Demomodus bricht der Start ab, wenn
  Admin-Passwort, Pepper, Geheimnisse oder TLS zu Keycloak nicht stimmen
  ([07-betrieb.md](07-betrieb.md), Abschnitt 3c).
- **Prüfungen im Build:** Modulgrenzen, Architekturregeln, OpenAPI-Snapshot und
  Kompatibilität der veröffentlichten API, Tests, Lint, `npm audit`, CodeQL und Playwright laufen in
  der CI ([08-projektrahmen.md](08-projektrahmen.md), Abschnitt 7).
- **Unabhängige Bewertung:** Drei Bewertungen und ein Sicherheitsaudit (2026-10-03, zwei hohe
  Befunde). Alle hohen und mittleren Befunde sind bearbeitet; was offen ist, steht in
  [offene-befunde.md](offene-befunde.md).

---

## 4) Was Demo ist

### Simulierte Fremdsysteme

Alle Fremdsysteme sind in dieser Instanz simuliert. Die Simulationen bauen die Sicherheit eines
echten Systems nicht nach. Was ein echtes System zusagen muss, steht in
[port-vertraege.md](port-vertraege.md).

| System | Heute | Was ein echtes System zusagen muss | `demoOnly` |
|---|---|---|---|
| **Personenverzeichnis** | simuliert, mit ungeschützter Verwaltungs-API | [Port-Vertrag](port-vertraege.md#personenverzeichnis-persondirectory-personmasterdata-activationcodes-invitations) | nein – der wichtigste Vertrag |
| **KOBIL** | simuliert; PIN und Aktivierungsgeheimnis im Klartext ([ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)) | [Port-Vertrag](port-vertraege.md#kobil-kobilkobilssms--demoonly) | ja |
| **Nect** | simuliert | [Port-Vertrag](port-vertraege.md#nect-nectnectident--demoonly) | ja |
| **eID-Server** | simulierte Kartenlesung | [Port-Vertrag](port-vertraege.md#eid-server-in-ident_eid-simuliert--demoonly) | ja |
| **SMS und E-Mail** | Postausgang im „Briefkasten“ | [Port-Vertrag](port-vertraege.md#zustellung-von-tan-und-code-sms-mail) | nein – der Kern prüft den Code selbst |

Auch der **Gerätefaktor** ist `demoOnly`, obwohl er kein Fremdsystem ist: Die Nutzerprüfung auf dem
Gerät behauptet nur ein JWT, das die App selbst signiert.

### Der Demomodus

`demo.mode` (Umgebungsvariable `DEMO_MODE`) ist standardmäßig an. Nur im Demomodus gibt es die
Demo-Oberflächen (Briefkasten, Mock-Postausgänge, Zurücksetzen), die `demoOnly`-Verfahren,
Demo-Werte in den Antworten ([ADR-28](adr/ADR-028-demo-werte-abschaltbar.md)), die Demo-Personen und
das Zurücksetzen der Datenbank ([07-betrieb.md](07-betrieb.md), Abschnitt 8).

### Frontends und Umgebung

- **Browser als Gerät:** Die Schlüssel liegen in IndexedDB, nicht in einem Secure Element. Ein
  Produktivsystem bräuchte eine native App mit hardwaregestütztem Schlüsselspeicher
  ([09-dpop.md](09-dpop.md)).
- **Ausführung:** `compose.yml` mit selbstsigniertem Zertifikat und ein OpenShift-Prototyp
  ([13-ausfuehren.md](13-ausfuehren.md)); beides ist Vorführrahmen.
- **Datenbank:** eine H2-Datei ([08-projektrahmen.md](08-projektrahmen.md), Abschnitt 4).

---

## 5) Was vor echten Personendaten fehlt

Die Liste ist nach Bereichen geordnet. Die Kennungen `DPoP-demo-…` sind Issues im Tracker (`bd show`).

**Umgebung und Frontend** (ADR-35, Bereich 3):

- TLS zwischen Keycloak und Orchestrator, Proxy-Header, Compose-Ports (`DPoP-demo-ai4x`).
- Keycloak im optimierten Startmodus mit festem Hostnamen, DB-Passwort per Secret (`DPoP-demo-9msv`),
  Admin-Geheimnis auf OpenShift (`DPoP-demo-x25a`).
- CSP im Frontend, Tokens des Web-Kanals, Thumbprint nach RFC 7638 (`DPoP-demo-dm2j`).

**Schlüssel und Daten:**

- Zielbild für Schlüsselverwaltung: KMS oder HSM, Rotation, Widerruf (`DPoP-demo-61kp`). Heute
  liegen Schlüssel in derselben Datenbank ([ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)).
- Personenbezogene Spalten sind nicht verschlüsselt
  ([Idee Umschlagverschlüsselung](ideen/verschluesselung-differenzierte-aufbewahrung.md),
  `DPoP-demo-bo1w`).
- Aufbewahrungsfristen mit Datenschutz und Compliance festlegen; heute sind es Richtwerte.

**Betrieb:**

- PostgreSQL statt H2 (`DPoP-demo-pi55`); Migrationen sind ab dem ersten produktiven Einsatz nur
  noch additiv.
- Sicherung und Wiederherstellung beschreiben und üben (`DPoP-demo-prnl`).
- Mehr als eine Instanz: gemeinsame Sperre für geplante Aufgaben, fester Pepper
  (`DPoP-demo-g7np`); die Replay-Tabelle für DPoP skaliert so nicht
  ([offene-befunde.md](offene-befunde.md) Abschnitt 8, „Erkannte, bewusst zurückgestellte
  Verbesserungen“).
- Dashboards und Alarme; heute gibt es Kennzahlen, aber keine Auswertung.

**Echte Fremdsysteme:** Jede Anbindung erfüllt ihren Port-Vertrag. Begonnen ist Nect
(`DPoP-demo-v033`, `DPoP-demo-z90h`); das Personenverzeichnis ist der wichtigste Vertrag, weil es
außerhalb des Demomodus der einzige Weg zu einer Identifizierung ist.

**Offene Befunde im Kern:** gesammelt in [offene-befunde.md](offene-befunde.md), dort auch die
Restrisiken. Vor einer produktiven Passwortanmeldung per Lookup: die Konto- und Personensperre bucht
einen Versuch vor der Prüfung statt danach ([07-betrieb.md](07-betrieb.md) Abschnitt 4,
„Restrisiko: parallele Versuche“, `DPoP-demo-164n.29`).

---

## 6) Wie es weitergeht

Ein Vorschlag für die Reihenfolge, abgeleitet aus ADR-35 und den
[offenen Befunden](offene-befunde.md). Er ist nicht entschieden.

Am wichtigsten sind die ersten beiden Schritte. Die Doku beschreibt ein Zielbild, das bisher im
Projekt entstanden ist; bevor weiter gehärtet und angebunden wird, müssen die, die es fachlich und
technisch tragen sollen, es mit abgestimmt haben.

1. **Mit Fachexperten Domäne, Regeln und Journeys abstimmen.** Stimmen Begriffe, Konten und
   Zustände ([Domänenmodell](02-domaenenmodell.md), [Glossar](glossar/glossar.md)), die Regeln für
   Sicherheitsniveaus und Verfahren ([Orchestrierung und Policy](04-orchestrierung.md)) und die
   Abläufe je Intent ([Journeys](journeys/))? Was hier abweicht, ändert das Modell; das ist jetzt
   noch billig.
2. **Mit Entwicklern Architektur, Konzepte und fehlende Anforderungen abstimmen.** Tragen
   Modulschnitt, Tool-Architektur, API und DPoP-Bindung ([Projektrahmen](08-projektrahmen.md),
   [Tool-Architektur](03-tool-architektur.md), [Architekturentscheidungen](12-entscheidungen.md))?
   Welche Anforderungen fehlen noch, etwa an Schnittstellen zu Fachdiensten, Mandanten, Last oder
   Barrierefreiheit? Offene Konzepte stehen unter [Ideen](ideen/).
3. **Den Kern abschließen.** Die [offenen Befunde](offene-befunde.md), zuerst Sicherheit und
   Keycloak (Abschnitte 1 und 2), und die offenen Invarianten. Der Kern bleibt
   das Argument; er muss jeder genauen Prüfung standhalten.
4. **Grundsatzentscheidungen treffen.** Schlüsselverwaltung (`DPoP-demo-61kp`),
   Verschlüsselung gespeicherter Daten (`DPoP-demo-bo1w`), Zieldatenbank (`DPoP-demo-pi55`) und ob
   mehr als eine Instanz laufen soll. Sie berühren Modell und Migrationen und werden teurer, je später
   sie fallen.
5. **Umgebung härten** (ADR-35, Bereich 3): TLS, Keycloak-Startmodus, Proxy, Admin-Zugänge,
   Sicherung. Ziel ist eine Umgebung, in der `demo.mode=false` startet und bleibt.
6. **Echte Fremdsysteme anbinden**, eines nach dem anderen, jeweils gegen seinen Port-Vertrag:
   zuerst das Personenverzeichnis, dann die Identifizierung (Nect, eID), dann KOBIL. Ein Verfahren
   verliert `demoOnly` erst, wenn sein echtes System den Nachweis liefert.
7. **Die App als native App.** Das Browser-Frontend zeigt die Abläufe; ein produktives Gerät braucht
   einen hardwaregestützten Schlüsselspeicher.
8. **Freigabe für echte Personendaten.** Die Einschränkung aus ADR-35 fällt erst, wenn 1 bis 7
   erledigt sind und Datenschutz, Sicherheit und Betrieb zugestimmt haben.
