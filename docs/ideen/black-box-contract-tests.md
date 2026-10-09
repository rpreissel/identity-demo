# Idee: Black-Box-Contract-Tests

**Worum es geht.** Eine Testsuite soll das System nur von außen über HTTP prüfen, so wie es ein
echter Client sieht. Sie hält das heutige Verhalten als Vertrag (Contract) fest. Eine
Neuentwicklung, egal in welcher Programmiersprache, gilt als gleichwertig, wenn sie diese Suite
besteht.

**Warum das wichtig ist.** Die vorhandenen Tests schauen alle in irgendeiner Form in das System
hinein oder ersetzen Teile davon (Abschnitt 1). Keiner prüft, ob das unveränderte System sich von
außen so verhält, wie ein Client es erwartet. Ohne einen solchen Test lässt sich nicht belegen,
dass eine Neuentwicklung dasselbe tut.

**Stand: Konzept, nicht umgesetzt** (Stand 2026-10-02).

---

## 1) Ausgangslage

Es gibt viele Tests, aber keiner prüft das unveränderte System von außen über die API:

- Die Integrationstests (`IntegrationTestSupport`) rufen die App zwar über echtes HTTP auf. Sie
  ändern aber einiges am System:
  - Sie ersetzen die DPoP-Prüfung durch eine Attrappe (`DPoP: mock-dpop-token`).
    [DPoP](../glossar/glossar.md) ist der Standard, mit dem jede Anfrage der App belegt, dass sie
    vom Besitzer eines bestimmten Schlüssels kommt.
  - Sie löschen den Zustand per SQL.
  - Sie legen Konten direkt über Domänendienste an.
  - Sie ersetzen `PeerAuthValidator` durch einen Stub.
  - Sie legen den Katalog der Tools fest auf einen bestimmten Stand.
- `ModelBasedJourneyTest` schickt zufällige Folgen von Schritten über HTTP. Seine Invarianten prüft
  er aber in der Datenbank. Außerdem verändert er das Alter von Sitzungen direkt im Prozess.
- Die Playwright-Specs in `frontend/e2e/` laufen wirklich von außen, aber über die Oberfläche. Sie
  decken nur wenige Abläufe ab.
- `OpenApiSnapshotTest` und `checkPublishedApiCompatibility` sichern die **Form** der API
  (`api/openapi.yaml`, `api/published/v1.yaml`). Die Integrationstests prüfen zusätzlich jeden
  Erfolgsstatus gegen den Vertrag (`ContractStatusCheck`), aber nur im eigenen Prozess.

Keine dieser Prüfungen kontrolliert, welche Schritte aufeinander folgen. Auch Regeln wie die
Kanalbindung prüft keine von ihnen.

## 2) Ziel und Abgrenzung

**Zum Contract gehört alles, was ein Client beobachten kann:**

- Statuscode, `Location`-Header und die Form des Bodys jeder Operation gemäß `api/openapi.yaml`.
- Der Fehlervertrag: Fehler kommen als `ErrorResponse {error, text}`, und jeder Fehlercode gehört
  fest zu einem Status (von `BAD_REQUEST` 400 bis `INTERNAL_ERROR` 500).
- Die Folge von `next` und `stepData` je Intent. `next` sagt dem Client, welcher Schritt als
  Nächstes kommt, `stepData` liefert die Daten dafür. Ein [Intent](../glossar/glossar.md) ist das
  Anliegen des Nutzers, etwa sich registrieren oder sich anmelden. Zum Contract gehört also, welcher
  Schritt auf welche Eingabe folgt. Das schließt Fehlversuche ein (200 mit `failed-attempt`, kein
  Fehlerstatus).
- Die DPoP-Regeln: 401 bei fehlendem, ungültigem, veraltetem oder wiederverwendetem Proof. Ein
  Proof ist der signierte Beleg, den die App bei jeder Anfrage mitschickt.
- Die [Kanalbindung](../glossar/glossar.md): 403 `BINDING_MISMATCH`, wenn eine Anfrage mit einem
  fremden Schlüssel kommt.
- Token und Claims (`acr`, `amr`, idclaims, Attribute), soweit sie in Antworten sichtbar sind.
  `acr` ist das erreichte Sicherheitsniveau, `amr` die Liste der genutzten Anmeldeverfahren.
- Im Web-Kanal zusätzlich die Signatur jeder Antwort im Header `Orchestrator-Response-Signature`.

**Nicht zum Contract gehören:** interne Namen von Zuständen, das Datenbankschema, Logs und der
Wortlaut von Texten. Texte prüft die Suite nur über ihren Schlüssel (`text.key`), nicht über die
ausgelieferte Übersetzung.

**Maßstab ist das heutige Verhalten.** Weicht es von der Doku ab, muss man das vor dem Festschreiben
im Einzelfall entscheiden (siehe Abschnitt 8).

## 3) Testoberfläche (Test-Contract)

Die Suite braucht Testzugänge, um Abläufe ohne echte Fremdsysteme durchzuspielen. Sie nutzt dafür
den Demomodus (`demo.mode=true`). Eine Neuentwicklung muss diese Testzugänge ebenfalls anbieten.
Sie sind **Teil des Vertrags** und nicht nur ein Hilfsmittel.

| Testzugang | Zweck |
|---|---|
| `demo`-Objekt in Antworten (`tan`, `password`, `persons`, `invitations`) | Codes und Testpersonen lesen |
| `/mock-sms/outbox`, `/mock-mail/outbox` | Zugestellte TANs und Links lesen |
| `/mock-personenverzeichnis/*` | Personen, Freischaltcodes und Einladungen anlegen |
| `/mock-nect/cases/*` | Nect-Fall abschließen, scheitern lassen oder abbrechen |
| `/mock-kobil/*` | Aktivierung, Login und Risikoereignis simulieren |
| `POST /orchestrator/demo/reset` | Zustand vor jeder Spec zurücksetzen |

Die Formen dieser Endpunkte sind schon in `api/modules/` beschrieben, je Simulation in einer
eigenen Datei. Sie gehören aber nicht zu `api/openapi.yaml`. Die Suite prüft sie deshalb gegen
diese Moduldateien.

Zeitabhängiges Verhalten bleibt vorerst draußen, also der Ablauf von Sitzungen, Sperrzeiten und
Mengenbegrenzungen über die Zeit. Dafür gibt es keinen Testzugang zur Uhr. Ein Demo-Endpunkt zum
Verstellen der Uhr ist eine offene Option (Abschnitt 8).

## 4) Aufbau der Suite

Die Suite ist ein eigenes Gradle-Subprojekt `contract-tests/`. Sie nutzt Kotest, den HTTP-Client
des JDK und Nimbus für JOSE (Signaturen und Schlüssel im JSON-Format).

- **Keine Abhängigkeit auf das Backend.** Das Subprojekt kennt weder Klassen noch Ressourcen aus
  `src/main`. Der Build erzwingt das: Es gibt kein `project(":")` in den Abhängigkeiten, und eine
  ArchUnit-Regel prüft es zusätzlich. Die einzige gemeinsame Eingabe ist `api/openapi.yaml` mit
  `api/modules/`.
- **Das Ziel ist frei wählbar.** Mit `-PcontractBaseUrl=…` läuft die Suite gegen ein beliebiges
  laufendes System. Ohne diese Angabe startet sie selbst `bootRun` mit einer frischen
  In-Memory-H2, so wie es `frontend/playwright.config.ts` tut.
- **Eine eigene Gradle-Aufgabe** `contractTest`, nicht Teil von `test`. So bleibt der normale
  Build schnell.

Die Suite besteht aus fünf Bausteinen:

1. **DPoP-Signer.** Ein Gerät ist ein P-256-Schlüsselpaar. Für jede Anfrage entsteht ein frischer
   Proof mit `typ=dpop+jwt`, öffentlichem `jwk`, `htm`, exakter `htu` (ohne Query), `iat` und neuer
   `jti`. Vorlage ist [`frontend/src/dpop.ts`](../../frontend/src/dpop.ts). Für die Negativtests
   kann der Signer gezielt falsch signieren: mit falschem Schlüssel, altem `iat`, wiederholter
   `jti` oder falschem `htm`.
2. **Geräte-Proofs** (`device-proof+jwt`) für Gerätebindung und Rebind. Damit belegt die App, dass
   sie den Schlüssel eines gebundenen Geräts besitzt. `DeviceBindingIntegrationTest` erzeugt solche
   Proofs heute schon mit Nimbus.
3. **Journey-Treiber.** Er folgt dem `next` der letzten Antwort, statt eine feste Klickfolge
   abzuspielen, so wie `completeRegistration()` in `frontend/e2e/journey.ts`. IDs ermittelt er nur
   aus `Location` und aus Antworten.
4. **Schema-Prüfung jeder Antwort** gegen `api/openapi.yaml`, etwa mit dem
   swagger-request-validator von Atlassian. Ein Verstoß lässt jeden Test scheitern, ohne dass man
   dafür eigens einen Test schreibt.
5. **Abdeckungsprüfung.** Die Suite merkt sich jede aufgerufene Operation (Methode und Pfadmuster).
   Sie schlägt fehl, wenn eine Operation aus `api/openapi.yaml` nie aufgerufen wurde. So fallen
   neue Endpunkte sofort auf.

## 5) Web-Kanal ohne Codeänderung

Im Web-Kanal ruft Keycloak den Orchestrator über eigene Endpunkte auf, die Keycloak-Fassade
(`/orchestrator/api/v1/kc/*`). Die Fassade erwartet bei jeder Anfrage ein signiertes
Peer-Auth-JWT von Keycloak. [Peer-Auth](../glossar/glossar.md) heißt: Keycloak und Orchestrator
weisen sich gegenseitig aus. Die Suite übernimmt dafür selbst die Rolle von Keycloak:

- Sie startet im Testprozess einen kleinen JWKS-Endpunkt, der ihre öffentlichen Schlüssel
  veröffentlicht. Das System startet sie mit den vorhandenen Properties
  `keycloak.peer-auth.issuer`, `keycloak.peer-auth.audience` und `keycloak.peer-auth.jwks-uri`
  (`src/main/resources/application.yml`, Block `keycloak.peer-auth`). Dafür ist keine Änderung am
  Code nötig.
- Sie signiert Peer-Auth-JWTs mit `iss`, `aud`, `htm`, `htu`, `jti` und `iat`, so wie es
  `PeerAuthRoundTripTest` schon tut.
- Sie prüft jede Antwort der Fassade über den Header `Orchestrator-Response-Signature` gegen
  `/orchestrator/api/v1/kc/response-jwks/.well-known/jwks.json`.

Damit gehören auch diese drei Properties zum Test-Contract. Eine Neuentwicklung muss sich also so
einstellen lassen, dass sie einem fremden Aussteller und dessen JWKS vertraut.

Die Keycloak-Erweiterung selbst (Authenticator, Storage-Provider, Grant) testet die Suite nicht.
Die Erweiterung ist ein Client dieses Vertrags. Für sie bleiben `frontend/e2e-keycloak/` und der
compose-Stack zuständig.

## 6) Testkatalog und Größenordnung

Grundlage der Schätzung: `api/openapi.yaml` enthält 99 Operationen auf 76 Pfaden, davon 8 Pfade im
Web-Kanal. Dazu kommen rund 24 Tools und 10 Intents mit eigenem Diagramm in `docs/journeys/`.

| Bereich | Inhalt | Tests (ca.) |
|---|---|---|
| DPoP und Bindung | Proof fehlt, falsche Signatur, falsches `htm`/`htu`, `iat` zu alt oder zu neu, Wiederholung (Replay), fremder Schlüssel ergibt 403 | 15 |
| Fehlervertrag | jeder Code mit Status und Form, unbekannte IDs, 410 nach Abbruch | 15 |
| Kanal-Lebenszyklus | 201 mit `Location`, GET, DELETE mit 204, Journey abbrechen, device-link, answer | 15–20 |
| Tools (je 4–6) | Erfolg, Fehlversuch, Sperre nach zu vielen Versuchen, back, delete, GET | 100–140 |
| Journeys | 2–5 Szenarien je Intent, von Anfang bis Ende über den Treiber | 30–50 |
| Token und Methoden | Token, idclaims, Attribute, Methoden auflisten und löschen, Step-up | 15–20 |
| Web-Kanal | PATCH anlegen und fortsetzen, flow-end, Konten, Einladungen, sign-outs, Ablehnung bei Peer-Auth, Antwortsignatur | 20–30 |
| Öffentliche Endpunkte | `/tools/catalog`, `/texts/{lang}`, mgmt-Passwort | 5–10 |

Zusammen sind das **200 bis 300 Testfälle**. Die Schema- und die Abdeckungsprüfung kommen ohne
eigene Testfälle dazu.

Als erste Stufe genügt ein **Minimalstand von 60 bis 80 Tests**:
- ein Aufruf je Operation
- die DPoP-Regeln
- der Fehlervertrag
- ein Erfolgsfall je Intent

Schon damit besteht eine Neuentwicklung die Suite nicht, wenn sie grob abweicht.

## 7) Determinismus

Die Suite soll bei jedem Lauf dasselbe Ergebnis liefern. Dafür gelten drei Regeln:

- **Zurücksetzen vor jeder Spec** über `POST /orchestrator/demo/reset`. Die Specs laufen
  nacheinander.
- **Die Auswahl der Tools legt der Client fest.** Die Integrationstests legen den Katalog im
  Prozess fest (`PinnedToolCatalogTestConfig`). Von außen geht das nicht. Die Suite gibt deshalb
  beim Anlegen eines Kanals `availableTools` ausdrücklich an. Listen angebotener Tools prüft sie
  nur im Verhältnis zu dieser Angabe. Den vollen Katalog prüft ein einzelner Test gegen
  `/tools/catalog`.
- **Keine Annahmen über IDs, Reihenfolge oder Uhrzeit.** IDs kommen aus `Location` und aus
  Antworten. Zeitwerte prüft die Suite nur auf Form und Plausibilität, zum Beispiel ob `exp` nach
  `iat` liegt.

## 8) Offene Fragen

- **Uhr.** Ohne Testzugang zur Uhr bleiben der Ablauf von Sitzungen und die Sperrzeiten
  ungetestet. Ein Demo-Endpunkt zum Verstellen der Zeit würde das lösen. Er würde aber den
  Test-Contract erweitern.
- **Heutiges Verhalten oder Zielbild?** Laut [AGENTS.md](../../AGENTS.md) hat die Doku Vorrang vor
  dem Code. Es gibt Abweichungen, etwa die in `DPoP-demo-hcdn`: Der Vertrag nennt Statuscodes, die
  der Server nie liefert. Solche Fälle müssen vor dem Festschreiben einzeln entschieden werden:
  Entweder wird der Code angepasst oder die Doku.
- **Versionierung des Contracts.** Wenn sich Verhalten bewusst ändert, muss die Suite angepasst
  werden. Denkbar ist eine Regel wie bei `api/published/v1.yaml`: Die Suite gehört zu einer
  API-Version, und nur ein neuer Major-Stand darf bestehende Tests brechen.
- **Ablösung bestehender Tests.** Einige Integrationstests prüfen dasselbe von innen. Offen ist,
  ob sie bleiben, weil sie schneller sind und Fehler genauer zeigen, oder ob sie zugunsten der
  Suite entfallen.

## 9) Stufen der Umsetzung

1. **Grundaufbau:** Subprojekt, Aufgabe `contractTest`, Start des Systems, DPoP-Signer, Schema- und
   Abdeckungsprüfung, Zurücksetzen.
2. **Minimalstand:** ein Aufruf je Operation, DPoP-Regeln, Fehlervertrag, ein Erfolgsfall je Intent.
3. **Web-Kanal:** eigener JWKS-Endpunkt, Signer für Peer-Auth, Prüfung der Antwortsignatur und die
   Fälle aus Abschnitt 6.
4. **Tiefe je Tool und Intent:** Tool für Tool die übrigen Fälle aus Abschnitt 6, für jedes Tool
   ein eigenes Issue in beads (Bead).
