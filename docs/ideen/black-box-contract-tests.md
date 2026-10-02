# Idee: Black-Box-Contract-Tests

Status: **Konzept, nicht umgesetzt** (Stand 2026-10-02). Das Dokument beschreibt eine Testsuite,
die das System nur von außen über HTTP prüft. Sie hält das heutige Verhalten als Vertrag fest:
Eine Reimplementierung, egal in welcher Sprache, gilt als gleichwertig, wenn sie die Suite besteht.

---

## 1) Ausgangslage

Es gibt viele Tests, aber keiner prüft das unveränderte System von außen über die API:

- Die Integrationstests (`IntegrationTestSupport`) rufen die App zwar über echtes HTTP auf. Sie
  ersetzen aber die DPoP-Prüfung durch einen Mock (`DPoP: mock-dpop-token`), löschen den Zustand
  per SQL, legen Konten über Domänendienste an, stubben `PeerAuthValidator` und pinnen den
  Tool-Katalog.
- `ModelBasedJourneyTest` schickt zufällige Schrittfolgen über HTTP, prüft seine Invarianten aber in
  der Datenbank und lässt Sitzungen im Prozess altern.
- Die Playwright-Specs in `frontend/e2e/` laufen wirklich von außen, aber über die Oberfläche. Sie
  decken nur wenige Abläufe ab.
- `OpenApiSnapshotTest` und `checkPublishedApiCompatibility` sichern die **Form** der API
  (`api/openapi.yaml`, `api/published/v1.yaml`). Die Integrationstests prüfen zusätzlich jeden
  Erfolgsstatus gegen den Vertrag (`ContractStatusCheck`), aber nur im eigenen Prozess. Folgen von
  Schritten und Regeln wie die Kanalbindung prüft keiner dieser Wächter.

## 2) Ziel und Abgrenzung

**Zum Contract gehört alles, was ein Client beobachten kann:**

- Statuscode, `Location`-Header und Body-Form jeder Operation gemäß `api/openapi.yaml`.
- Der Fehlervertrag: `ErrorResponse {error, text}` mit fester Zuordnung von Code zu Status
  (`BAD_REQUEST` 400 bis `INTERNAL_ERROR` 500).
- Die Folge von `next` und `stepData` je Intent, also welcher Schritt auf welche Eingabe folgt,
  einschließlich Fehlversuchen (200 mit `failed-attempt`, kein Fehlerstatus).
- DPoP-Regeln (401 bei fehlendem, ungültigem, veraltetem oder wiederverwendetem Proof) und die
  Kanalbindung (403 `BINDING_MISMATCH` bei fremdem Schlüssel).
- Token und Claims (`acr`, `amr`, idclaims, Attribute), soweit sie in Antworten sichtbar sind.
- Im Web-Kanal zusätzlich die Antwortsignatur `Orchestrator-Response-Signature`.

**Nicht zum Contract gehören:** interne Zustandsnamen, das Datenbankschema, Logs und der Wortlaut
von Texten. Texte prüft die Suite nur über ihren Schlüssel (`text.key`), nicht über die
ausgelieferte Übersetzung.

**Maßstab ist das heutige Verhalten.** Weicht es von der Doku ab, entscheidet man das vor dem
Festschreiben im Einzelfall (siehe Abschnitt 8).

## 3) Testoberfläche (Test-Contract)

Die Suite braucht Haken, um Abläufe ohne echte Fremdsysteme durchzuspielen. Sie nutzt dafür den
Demo-Modus (`demo.mode=true`). Eine Reimplementierung muss diese Oberfläche ebenfalls anbieten;
sie ist **Teil des Vertrags**, nicht nur ein Hilfsmittel:

| Haken | Zweck |
|---|---|
| `demo`-Objekt in Antworten (`tan`, `password`, `persons`, `invitations`) | Codes und Testpersonen lesen |
| `/mock-sms/outbox`, `/mock-mail/outbox` | Zugestellte TANs und Links lesen |
| `/mock-personenverzeichnis/*` | Personen, Freischaltcodes und Einladungen anlegen |
| `/mock-nect/cases/*` | Nect-Fall abschließen, scheitern lassen oder abbrechen |
| `/mock-kobil/*` | Aktivierung, Login und Risikoereignis simulieren |
| `POST /orchestrator/demo/reset` | Zustand vor jeder Spec zurücksetzen |

Die Formen dieser Endpunkte stehen schon in `api/modules/` (je Simulation eine Datei). Sie gehören
aber nicht zu `api/openapi.yaml`; die Suite prüft sie gegen diese Moduldateien.

Zeitabhängiges Verhalten (Ablauf von Sitzungen, Sperrzeiten, Rate-Limits über die Zeit) bleibt
vorerst draußen, weil es keinen Haken für die Uhr gibt. Ein Demo-Endpunkt zum Verstellen der Uhr
ist eine offene Option (Abschnitt 8).

## 4) Aufbau der Suite

Ein eigenes Gradle-Subprojekt `contract-tests/` mit Kotest, dem HTTP-Client des JDK und Nimbus für
JOSE.

- **Keine Abhängigkeit auf das Backend.** Das Subprojekt kennt weder Klassen noch Ressourcen aus
  `src/main`; der Build erzwingt das (kein `project(":")` in den Abhängigkeiten, dazu eine
  ArchUnit-Regel). Einzige gemeinsame Eingabe ist `api/openapi.yaml` mit `api/modules/`.
- **Ziel frei wählbar.** Mit `-PcontractBaseUrl=…` läuft die Suite gegen ein beliebiges laufendes
  System. Ohne Angabe startet sie selbst `bootRun` mit frischer In-Memory-H2, wie
  `frontend/playwright.config.ts` es tut.
- **Eigene Gradle-Aufgabe** `contractTest`, nicht Teil von `test`, damit der normale Build schnell
  bleibt.

Bausteine:

1. **DPoP-Signer.** Ein Gerät ist ein P-256-Schlüsselpaar. Für jede Anfrage entsteht ein frischer
   Proof mit `typ=dpop+jwt`, öffentlichem `jwk`, `htm`, exakter `htu` (ohne Query), `iat` und neuer
   `jti`. Vorlage ist [`frontend/src/dpop.ts`](../../frontend/src/dpop.ts). Für die Negativtests
   kann der Signer gezielt falsch signieren: falscher Schlüssel, altes `iat`, wiederholte `jti`,
   falsches `htm`.
2. **Geräte-Proofs** (`device-proof+jwt`) für Gerätebindung und Rebind, wie sie
   `DeviceBindingIntegrationTest` heute schon mit Nimbus erzeugt.
3. **Journey-Treiber.** Er folgt dem `next` der letzten Antwort, statt eine feste Klickfolge
   abzuspielen, wie `completeRegistration()` in `frontend/e2e/journey.ts`. IDs ermittelt er nur aus
   `Location` und Antworten.
4. **Schema-Prüfung jeder Antwort** gegen `api/openapi.yaml`, etwa mit dem
   swagger-request-validator von Atlassian. Ein Verstoß lässt jeden Test scheitern, ohne dass man
   ihn eigens schreibt.
5. **Abdeckungsprüfung.** Die Suite merkt sich jede aufgerufene Operation (Methode und Pfadmuster)
   und schlägt fehl, wenn eine Operation aus `api/openapi.yaml` nie aufgerufen wurde. Neue
   Endpunkte fallen so sofort auf.

## 5) Web-Kanal ohne Codeänderung

Die kc-Fassade (`/orchestrator/api/v1/kc/*`) erwartet ein signiertes Peer-Auth-JWT von Keycloak.
Die Suite spielt Keycloak selbst:

- Sie startet einen kleinen JWKS-Endpunkt im Testprozess und startet das System mit den
  vorhandenen Properties `kc.peer-auth.issuer`, `kc.peer-auth.audience` und `kc.peer-auth.jwks-uri`
  (`src/main/resources/application.yml`, Block `kc.peer-auth`). Dafür braucht es keine Änderung am
  Code.
- Sie signiert Peer-Auth-JWTs mit `iss`, `aud`, `htm`, `htu`, `jti` und `iat`, wie
  `PeerAuthRoundTripTest` es schon tut.
- Sie prüft jede Antwort der Fassade über den Header `Orchestrator-Response-Signature` gegen
  `/orchestrator/api/v1/kc/response-jwks/.well-known/jwks.json`.

Damit gehören auch diese drei Properties zum Test-Contract: Eine Reimplementierung muss sich auf
einen fremden Aussteller und dessen JWKS einstellen lassen.

Die Keycloak-Erweiterung selbst (Authenticator, Storage-Provider, Grant) testet die Suite nicht.
Sie ist ein Client dieses Vertrags; für sie bleiben `frontend/e2e-keycloak/` und der compose-Stack.

## 6) Testkatalog und Größenordnung

Grundlage: 99 Operationen auf 76 Pfaden in `api/openapi.yaml` (davon 8 Pfade im Web-Kanal), rund
24 Tools und 10 Intents mit eigenem Diagramm in `docs/journeys/`.

| Bereich | Inhalt | Tests (ca.) |
|---|---|---|
| DPoP und Bindung | fehlt, falsche Signatur, falsches `htm`/`htu`, `iat` zu alt oder zu neu, Replay, fremder Schlüssel ergibt 403 | 15 |
| Fehlervertrag | jeder Code mit Status und Form, unbekannte IDs, 410 nach Abbruch | 15 |
| Kanal-Lebenszyklus | 201 mit `Location`, GET, DELETE mit 204, Journey abbrechen, device-link, answer | 15–20 |
| Tools (je 4–6) | Erfolg, Fehlversuch, Sperre nach zu vielen Versuchen, back, delete, GET | 100–140 |
| Journeys | 2–5 Szenarien je Intent, End-to-End über den Treiber | 30–50 |
| Token und Methoden | Token, idclaims, Attribute, Methoden auflisten und löschen, Step-up | 15–20 |
| Web-Kanal | PATCH anlegen und fortsetzen, restore-data, Konten, Einladungen, sign-outs, Peer-Auth-Ablehnung, Antwortsignatur | 20–30 |
| Öffentliche Endpunkte | `/tools/catalog`, `/texts/{lang}`, mgmt-Passwort | 5–10 |

Zusammen **200 bis 300 Testfälle**. Die Schema- und Abdeckungsprüfung kommen ohne eigene Testfälle
dazu.

Als erste Stufe genügt ein **Minimalstand von 60 bis 80 Tests**:
- ein Aufruf je Operation
- die DPoP-Regeln
- der Fehlervertrag
- ein Erfolgsfall je Intent

Damit fällt eine Reimplementierung schon bei groben Abweichungen durch.

## 7) Determinismus

- **Reset vor jeder Spec** über `POST /orchestrator/demo/reset`; Specs laufen nacheinander.
- **Tool-Auswahl über den Client.** Die Integrationstests pinnen den Katalog im Prozess
  (`PinnedToolCatalogTestConfig`). Von außen geht das nicht. Die Suite gibt deshalb beim Anlegen
  eines Kanals `availableTools` ausdrücklich an und prüft Kandidatenlisten nur relativ zu dieser
  Angabe. Den vollen Katalog prüft ein einzelner Test gegen `/tools/catalog`.
- **Keine Annahmen über IDs, Reihenfolge oder Uhrzeit.** IDs kommen aus `Location` und Antworten;
  Zeitwerte prüft die Suite nur auf Form und Plausibilität (z. B. `exp` nach `iat`).

## 8) Offene Fragen

- **Uhr.** Ohne Haken für die Uhr bleiben Ablauf und Sperrzeiten ungetestet. Ein Demo-Endpunkt zum
  Verstellen der Zeit würde das lösen, erweitert aber den Test-Contract.
- **Heutiges Verhalten oder Zielbild?** Laut [AGENTS.md](../../AGENTS.md) hat die Doku Vorrang vor
  dem Code. Abweichungen wie die in `DPoP-demo-hcdn` (Statuscodes im Vertrag, die der Server nie
  liefert) müssen vor dem Festschreiben je Fall entschieden werden: Code oder Doku anpassen.
- **Versionierung des Contracts.** Wenn sich Verhalten bewusst ändert, muss die Suite mitgehen.
  Denkbar ist eine Regel wie bei `api/published/v1.yaml`: Die Suite gehört zu einer API-Version,
  und nur ein neuer Major-Stand darf bestehende Tests brechen.
- **Ablösung bestehender Tests.** Einige Integrationstests prüfen dasselbe von innen. Ob sie
  bleiben (schneller, genauer bei Fehlern) oder zugunsten der Suite entfallen, ist offen.

## 9) Stufen der Umsetzung

1. **Gerüst:** Subprojekt, `contractTest`, Start des Systems, DPoP-Signer, Schema- und
   Abdeckungsprüfung, Reset.
2. **Minimalstand:** ein Aufruf je Operation, DPoP-Regeln, Fehlervertrag, ein Erfolgsfall je Intent.
3. **Web-Kanal:** eigener JWKS-Endpunkt, Peer-Auth-Signer, Prüfung der Antwortsignatur und die
   Fälle aus Abschnitt 6.
4. **Tiefe je Tool und Intent:** Tool für Tool die übrigen Fälle aus Abschnitt 6, je Tool ein Bead.
