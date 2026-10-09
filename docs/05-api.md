# API-Spezifikation

Dieses Dokument beschreibt die öffentliche Schnittstelle (API) des Orchestrators. Der Orchestrator
ist der Server dieses Projekts: Er entscheidet, welche Schritte ein Nutzer bei Anmeldung,
Registrierung und Verwaltung seines Kontos durchläuft. Seine API liegt unter zwei Pfaden,
`/orchestrator/api/v1` und `/tools/api`. Zwei Arten von Clients nutzen sie, je über einen eigenen
**Kanal**, also eine eigene Art der Verbindung zum Orchestrator ([Glossar](glossar/glossar.md)):

- den **App-Kanal**: Die Smartphone-App spricht direkt mit dem Orchestrator, und der Orchestrator
  führt den Ablauf.
- den **Web-Kanal**: Auf der Website führt Keycloak die Anmeldung und fragt den Orchestrator im
  Hintergrund, welche Schritte nötig sind.

Wer liest was:

- **Abschnitt 1** gilt für alle. Er beschreibt die beiden Pfadräume und ihre Versionen, die
  Grundsätze, das eine gemeinsame Antwortformat, wie sich ein Aufrufer ausweist, wie Texte
  übertragen werden und was zum Vertrag unter `api/` gehört.
- **Abschnitt 2** ist für Tool-Entwickler. Ein **Tool** ist ein abgeschlossener Arbeitsschritt, den
  der Nutzer durchläuft, etwa „SMS einrichten“ oder „mit Passwort anmelden“. Der Abschnitt zeigt,
  welche Pfade ein Tool hat, wie ein Durchlauf aussieht, wie man ein Tool verlässt und wann ein Tool
  eine neue Fassung braucht. Die Besonderheiten der einzelnen Tools stehen auf den
  [Verfahrensseiten](verfahren/README.md).
- **Abschnitt 3** ist für Orchestrator-Entwickler, also für alle, die einen Kanal führen. Das sind
  die App (3a, App-Kanal) und die Keycloak-Erweiterung (3b, Web-Kanal).
- **Abschnitt 4** beschreibt den Rest: Betriebs- und Demo-Endpunkte, die Stellvertreter externer
  Systeme und wie der Vertrag entsteht und geprüft wird.

Jede Antwort enthält ein Feld `next`. Es sagt dem Client, welcher Schritt als Nächstes kommt. Was
die Antworten fachlich bedeuten, insbesondere `next`, ergibt sich aus
[04-orchestrierung.md](04-orchestrierung.md). Wie ein Tool als Modul gebaut ist, steht in
[03-tool-architektur.md](03-tool-architektur.md).

---

## 1) Allgemeine Mechanismen

### Zwei Pfadräume und ihre Versionen

Orchestrator und Tools haben getrennte Versionen. Beide stehen als Segment im Pfad: Der
Orchestrator liegt unter `/orchestrator/api/v1`, jedes Tool unter `/tools/api/<toolId>/v<N>`
([ADR-50](adr/ADR-050-api-versionierung-umschlag-und-tool.md),
[ADR-51](adr/ADR-051-versionen-als-pfadsegment.md)). Versioniert wird also auf zwei Ebenen, die
voneinander unabhängig sind:

- Der **Umschlag** ist der Teil der API, den alle Tools gemeinsam nutzen. Dazu gehören das
  Antwortformat `ChannelResponse`, die Endpunkte des Kanals, `tools/catalog`, `texts`, die
  gemeinsamen `StepData`-Formen und das Verlassen eines Tools und die Rückkehr aus ihm. Der Umschlag
  steckt in jeder Antwort. Seine Version steht in `/orchestrator/api/v<N>`. Eine inkompatible
  Änderung daran trifft alle Clients. Eine neue Version ist für alle Clients ein Pflichtupdate, weil
  der Server immer nur eine Version des Umschlags anbietet.
- Ein **Tool** liegt mit seinen Pfaden, Schemas und den Formen seines Moduls unter
  `/tools/api/<toolId>/v<N>`. Die Versionen eines Tools heißen **Fassungen**. Welche Fassungen es
  gibt, deklariert das Tool in seinem Modul (`versions = setOf(1)`). Ein Client spricht je Tool genau
  eine Fassung und nennt sie in `availableTools`. Eine inkompatible Änderung an einer Fassung trifft
  nur die Clients, die diese Fassung sprechen. Man ändert ein Tool deshalb möglichst so, dass nur
  etwas hinzukommt. Geht das nicht, bekommt das Tool eine neue Fassung, und die alte läuft daneben
  weiter. Wann das nötig ist, steht in Abschnitt 2, „Fassungen eines Tools“.

Was nur Keycloak aufruft, liegt unter `/kc/`. Dieser Teil wird nicht eingefroren, also nicht als
veröffentlichter Stand festgehalten, gegen den spätere Änderungen geprüft werden. Der Grund: Die
Keycloak-Erweiterung wird zusammen mit dem Server ausgeliefert. Die meisten Änderungen fügen nur
etwas hinzu und brauchen nichts davon.

### Grundsätze

- Verschiedene Verfahren und Modi haben jeweils eigene, konkrete Endpunkte. Was eine Anfrage
  bewirkt, bestimmt die URL, nicht der Inhalt der Anfrage.
- Endpunkte, DTOs und Handler, die für ein bestimmtes Verfahren gebaut und klar benannt sind
  (`ident-fsc`, `enroll-sms`, `auth-sms`), haben Vorrang vor allgemeinen Sammel-Endpunkten.
- Vorbereitende Schritte arbeiten mit Tool-Ressourcen. Ein `POST` auf das Tool, mit dem Kanal als
  Parameter in der Query, legt eine Tool-Sitzung an, also einen einzelnen Durchlauf dieses Tools.
  Danach gestaltet das Tool seinen eigenen URL-Bereich selbst (Abschnitt 2).
- Ein `PATCH` enthält nur das, was der Client nachliefert oder ändert. Vorhandene Felder darf er
  gezielt überschreiben.
- HTTP-Fehlercodes sind für gestörte Abläufe reserviert. Es kann sein, dass Pflichtdaten fehlen
  oder dass ein Versuch fehlschlägt, aber noch weitere Versuche übrig sind. Dann antwortet der
  Orchestrator mit `200` und einem `next`. Die Regel dazu steht in
  [Orchestrierung](04-orchestrierung.md).
- Das Zielbild verwendet kein HATEOAS. Die Antworten enthalten also keine Links auf mögliche
  Folgeaufrufe.
- Welchen Aufruf der Client als Nächstes macht, leitet er über eine feste Routing-Tabelle ab. Dazu
  nimmt er `next.type` (`tool` oder `orchestrator`) und das passende Attribut, also `next.toolId`
  bzw. `next.context`, zusammen mit dem gewählten Eintrag aus `stepData.options`.
- Welcher Ablauf fachlich läuft, entscheidet das Backend. Es richtet sich dabei nach dem Zustand des
  Kanals, dem Status des Kontos und seinen Regeln. Beschrieben wird der Ablauf durch den **Intent**
  (`AuthIntent`), also das Anliegen des Nutzers, etwa „registrieren“ oder „anmelden“, und durch die
  Strategie dazu, also die Regeln dieses Intents. Es gibt keinen eigenen Typ wie `REGISTRATION` oder
  `LOGIN`.
- Damit alles konsistent bleibt, läuft je Kanal (`channelSessionId`) höchstens eine **Journey**
  aktiv. Eine Journey ist ein laufender Ablauf zu einem Intent, etwa eine Registrierung von der
  Identifizierung bis zum fertigen Konto. Welche Journey läuft, entscheidet das Backend.

### Das Antwortformat: `ChannelResponse`

**Alle Endpunkte antworten im selben Format**, der `ChannelResponse`:
`{ "channel": {channelSessionId, channelType, state, hasProvenFactor, currentAcr, currentAmr, activeMethods}, "next": {...}, "stepData": {...}, "demo": {...} }`.

Die Antwort hat vier Teile: `channel` beschreibt den Kanal, `next` nennt den nächsten Schritt,
`stepData` enthält, was der aktuelle Schritt zum Anzeigen braucht, und `demo` enthält Werte, die
nur der Vorführung dienen. Im Einzelnen:

- `channelSessionId`, `channelType`, `state` und `hasProvenFactor` stehen in jeder Antwort.
  `hasProvenFactor` sagt, ob auf diesem Kanal schon ein Faktor nachgewiesen ist.
- `currentAcr` ist das **Niveau**, das die Sitzung gerade erreicht hat, also wie sehr der Anmeldung
  vertraut wird (`loa1` bis `loa3`). `currentAmr` ist die Liste der Verfahren, mit denen sie es
  erreicht hat, und `activeMethods` die Liste der eingerichteten Verfahren des Kontos. Diese drei
  Felder stehen NIE in den Antworten eines Tools (`POST /tools/api/{toolId}/v{N}` sowie
  `PATCH`/`GET`/`DELETE` auf `/tools/api/...`). Sie kommen nur von den Endpunkten des Kanals
  (`GET`/`POST /channels`, `step-ups`, `enrollments`, `DELETE .../methods/{methodInstanceId}`).
- Das `next`-Objekt ist eine reine Adresse. Es sagt, wohin es weitergeht, und enthält nie Inhalte.
  Es gibt zwei Formen:
  - Tool-Schritt: `{ "type": "tool", "toolId": "...", "step": "...", "toolSessionId": "..." }`
  - Seite des Orchestrators (Auswahl, Bestätigung, Abschluss):
    `{ "type": "orchestrator", "context": "...", "step": "..." }`

  Die `toolSessionId` kennzeichnet einen einzelnen Durchlauf eines Tools (`ToolSession`). Zusammen
  mit der `toolId` und der Fassung, die der Client für dieses Tool spricht, ergibt sie die Adresse
  der Tool-Ressource (`/tools/api/{toolId}/v{N}/{toolSessionId}`). Sie ist gesetzt, sobald es für
  diesen Schritt eine `ToolSession` gibt. Das gilt auch, wenn der Client mitten in einem laufenden
  Tool fortsetzt (`GET /channels/{channelSessionId}`).
- **Werte von `channel.state`**: Die Tabelle zeigt alles, was der Client aus `state` ablesen kann,
  ohne einen Endpunkt aufzurufen.

  | Wert | Bedeutung für den Client |
  |---|---|
  | `ANONYMOUS` | Der Kanal ist offen, aber niemand ist angemeldet, und es ist kein Konto im Aufbau. `next` zeigt auf die Identifizierung oder den Login. |
  | `REGISTERING` | Der Kanal arbeitet mit einem Konto im Aufbau, das noch kein Anmeldeverfahren hat ([ADR-46](adr/ADR-046-konto-im-aufbau.md)). Ein Abbruch (`DELETE .../journey`) verwirft dieses Konto samt Identität und Adresse und führt zurück auf `ANONYMOUS`. Der Wert wird abgeleitet und nie gespeichert. Ein Client kann daran entscheiden, ob er vor einem Abbruch noch einmal nachfragt. |
  | `AUTHENTICATED` | Das Konto ist bekannt, und das aktuelle Niveau reicht aus. `logout`, `methods` und `enrollments` sind verfügbar. |
  | `STEP_UP_REQUIRED` / `STEP_UP_IN_PROGRESS` | Ein höheres Niveau ist nötig, oder der Nachweis dafür läuft schon. `next` zeigt den fälligen Schritt. Ein Abbruch führt direkt zurück auf `AUTHENTICATED`. |
  | `LOGGED_OUT` | Endzustand: Dieser Kanal ist beendet, und `next` fehlt. Für einen neuen Kanal braucht es einen neuen `POST /channels`. |
  | `EXPIRED` | Endzustand: Die Keycloak-Sitzung ist abgelaufen oder, vor der Anmeldung, die Lebensdauer des Kanals. Für den Client ist das dasselbe wie `LOGGED_OUT`. |

  Das Zustandsdiagramm steht im [Domänenmodell](02-domaenenmodell.md), Abschnitt 3.
- Auswahlmöglichkeiten stehen nicht in `next`, sondern in `stepData.options`. Dort stehen sie als
  vollständige `toolId`-Werte (z. B. `enroll-sms`). Der Client darf eine `toolId` nie selbst
  zusammensetzen. Er nimmt sie immer aus `next.toolId` oder aus `stepData.options`.
- Ist genau ein Verfahren erlaubt, überspringt das Backend die Auswahlseite und liefert direkt den
  Tool-Schritt.
- `stepData` enthält, was der aktuelle Schritt zum Anzeigen braucht: den Zustand innerhalb des
  Tools (z. B. `missingFields`), die erlaubten nächsten Tools (`options`) und nach einem
  fehlgeschlagenen Versuch den Grund (`error`). Gibt es nichts davon, fehlt das Feld.
- Jede `stepData` nennt im Feld `kind`, welche Form sie hat. Es gibt allgemeine Formen
  (`select-method`, `missing-fields`, `failed-attempt`, `confirm`, `message`) und Formen, die
  einzelne Module mitbringen, etwa `qr-pairing` oder `kobil-otp`. Welche Formen es gibt, steht in
  der Spec im `discriminator.mapping` von `StepData`. Für den Aufbau gilt:
  - Jede Form führt `kind` selbst als Pflichtfeld, und zwar mit genau ihrem eigenen Wert (ein
    `enum` mit einem einzigen Wert). `StepData` selbst ist nur ein `oneOf` mit dem
    Unterscheidungsmerkmal und hat keine eigenen Properties.
  - Jedes Modul deklariert seine Formen selbst, direkt neben ihren Klassen. Dazu legt es eine Map
    an, die jedem `kind` seine Form zuordnet, mit Beschreibung und Beispielwerten für den Vertrag
    (`"kobil-otp" to stepData<KobilOtpStep>(…)`). Eine zentrale Liste gibt es nicht.
  - Die Klassen selbst haben keine Annotationen. Wie sie übertragen werden, legt
    `StepDataWireFormat` einmal für alle fest. Ist ein `kind` doppelt vergeben, startet die
    Anwendung nicht.
  - Ein Client muss mit einem unbekannten `kind` rechnen. Er überspringt es dann, statt
    abzubrechen.
- In allen übertragenen Daten heißt das Unterscheidungsmerkmal `kind`, auch bei `Prompt` und
  `KobilUnlockCredential`. Den Namen `@t` gibt es nur intern, für die gespeicherten Zustände der
  Journeys. Nach außen wird er nicht verwendet, weil der TypeScript-Generator ihn nicht abbilden
  kann und daraus `t` macht.
- Im Web-Kanal enthält die Antwort zusätzlich `authData`, im App-Kanal (`APP`) nie (Abschnitt 3b).

### Aufrufer ausweisen

Der Orchestrator muss bei jeder Anfrage wissen, wer sie schickt. Das geschieht je Kanal anders:

- **App-Kanal:** Jede Anfrage enthält den Header `DPoP: <proof>`. DPoP ist ein Standard, mit dem
  eine Anfrage belegt, dass sie vom Besitzer eines bestimmten Schlüssels kommt. Der Schlüssel liegt
  auf dem Gerät ([DPoP-Bindung](09-dpop.md)).
- **Web-Kanal:** Keycloak weist sich nicht mit einem DPoP-Proof aus, sondern mit einer signierten
  Peer-Auth-Assertion im Header `Authorization`. Eine Assertion ist eine signierte Aussage, hier:
  „Diese Anfrage kommt von Keycloak.“ mTLS, also die gegenseitige Prüfung von TLS-Zertifikaten,
  wird dafür nicht verwendet (ADR-7). Aufbau und Prüfung beschreibt Abschnitt 3b, „Peer-Auth“.

Zwei Endpunkte kommen ohne DPoP aus: der Tool-Katalog (`GET /orchestrator/api/v1/tools/catalog`,
Abschnitt 3a) und die Texte (`GET /orchestrator/api/v1/texts/{lang}`, unten).

### Texte (`GET /orchestrator/api/v1/texts/{lang}`)

Fertigen Wortlaut enthält eine Antwort nur als Notbehelf (siehe `template` unten). Überall, wo
Menschen etwas lesen, steht stattdessen eine Text-Referenz. Das betrifft diese Felder:

- `ErrorResponse.text`
- `FailedAttemptStep.error`
- `MessageStep.message`
- `SelectMethodStep.title/description`
- `Prompt.*`
- `JourneyDebugStep.note`

Eine Text-Referenz sieht so aus:
`{ "key": "3f9a1c0b2e7d", "args": {"n": "3"}, "texts": {"grund": [{ "key": … }]} }`.

Der Client schlägt `key` im Bundle nach, also in der Sammlung aller Texte einer Sprache. Dann setzt
er die Platzhalter `{name}` ein. Werte aus `args` übernimmt er unverändert. Referenzen aus `texts`
löst er zuerst selbst auf; stehen mehrere unter einem Namen, verbindet er sie mit „, ". Eine
unbekannte ID zeigt er so an, wie sie ist.

Solange ein Text noch nicht in jede Sprache übersetzt ist, enthält die Referenz zusätzlich das Feld
`template`. Es enthält die deutsche Vorlage aus dem Code (`Text.toRef`, geprüft über
`TextBundle.wordedEverywhere`). Der Client zeigt dann diese Vorlage statt der ID. Sobald
`/translate-texts` gelaufen ist, entfällt das Feld von selbst. Eine vollständig übersetzte Referenz
enthält nur `key`, `args` und `texts`.

Das Bundle holt der Client beim Start mit `GET /orchestrator/api/v1/texts/{lang}`. Dafür braucht er
kein DPoP. Unterstützt sind `de` und `en`; für jede andere Sprache kommt `de`. Der Header
`Content-Language` nennt, welche Sprache geliefert wurde. Die Antwort hat die Form
`{ id: Wortlaut }` und enthält ein ETag. Beim nächsten Start schickt der Client `If-None-Match` mit;
die Antwort 304 heißt dann „unverändert“. Hintergrund und Pflege der Texte beschreibt ADR-33.

Die Keycloak-Erweiterung holt das Bundle wie jeden anderen Aufruf mit einer Peer-Auth-Assertion und
prüft die signierte Antwort (Abschnitt 3b). Der Grund: Die Texte bestimmen, was die Anmeldeseite
sagt. Deshalb darf niemand auf dem Übertragungsweg sie verändern.

Die Stellvertreter der Fremdsysteme liefern ihre eigenen Texte auf dieselbe Weise (Abschnitt 4).

### Der Vertrag: `api/`

Der Vertrag ist die maschinenlesbare Beschreibung der API im Format OpenAPI. Drei Stellen nutzen
ihn: die Kotlin-DTOs selbst, das Frontend und `keycloak-extension` (`OrchestratorClient`). Alle drei
beruhen auf demselben erzeugten Vertrag
([ADR-26](adr/ADR-026-api-vertrag-wird-generiert.md)). So kommt eine geänderte Antwort überall
zugleich an.

| Datei | Inhalt | Wofür |
|---|---|---|
| `api/openapi.yaml` | der App-Vertrag: alles unter `/orchestrator/api/v1` und `/tools/api` | Eingabe für beide Generatoren |
| `api/contract/envelope.yaml`, `api/contract/tools/<toolId>/v<N>.yaml` | die versionierten Teile des App-Vertrags: der Umschlag und je Tool und Fassung eine Datei, ohne `/kc` | das, was `checkPublishedApiCompatibility` vergleicht |
| `api/published/v1/envelope.yaml`, `api/published/tools/<toolId>/v<N>.yaml` | der veröffentlichte Stand dieser Teile | Vergleichsbasis für `checkPublishedApiCompatibility` |
| `api/modules/<modul>.yaml` | alle Endpunkte und eigenen DTOs dieses Moduls; gemeinsame Schemas per `$ref` auf `../openapi.yaml` | zum Lesen und für Reviews |
| `frontend/src/generated/` | die daraus erzeugten TypeScript-Typen | werden vom Frontend importiert und sind eingecheckt |
| `keycloak-extension/build/generated/` | die daraus erzeugten Java-Modelle | werden von `OrchestratorClient` benutzt und sind nicht eingecheckt |

**Was zum App-Vertrag gehört, entscheidet der Pfad.** `api/openapi.yaml` enthält genau die
Endpunkte unter `API_V1` und `TOOLS_API`. Das ergibt sich aus diesen Konstanten, nicht aus einer
Liste von Ausnahmen. Drei Arten von Endpunkten liegen bewusst außerhalb. Abschnitt 4 beschreibt
sie:

- Betriebsendpunkte unter `/orchestrator/admin`. Mit ihnen kann der Betreiber Tools sperren, die
  Reihenfolge der Registrierung festlegen, den Journey-Trace aller Konten lesen, aktive Sitzungen
  ansehen, Konten löschen und die Demo zurücksetzen. Nur diese Endpunkte verlangen eine Anmeldung (HTTP
  Basic mit `demo.admin.*`).
- Der öffentliche Server-Status unter `/orchestrator/demo/server-info`. Er wird nur gelesen. Zustand
  und Kennzahlen im Block `operations` liefert er nur im Demomodus, also wenn die Anwendung zum
  Vorführen läuft. Daneben liegen dort für die Startseite `GET /orchestrator/demo/sessions` und
  `POST /orchestrator/demo/reset`. Die Sitzungen und das Zurücksetzen gibt es nur im Demomodus
  (`@DemoSurface`).
- Die Stellvertreter externer Systeme unter `/mock-*` (`/mock-kobil`,
  `/mock-personenverzeichnis`, `/mock-nect`, ADR-31). Sie simulieren in der Demo die Fremdsysteme.

Auf keinen dieser Endpunkte darf sich ein App-Client verlassen. Stünden sie im eingefrorenen Stand,
würde der Kompatibilitätsvergleich es als Bruch des App-Vertrags melden, wenn man sie später
entfernt. Beschrieben sind sie trotzdem, und zwar in der Datei ihres Moduls unter `api/modules/`.

Wie der Vertrag entsteht, wie er gegen den veröffentlichten Stand geprüft wird und wie man eine
Änderung übernimmt (`./gradlew updateOpenApiSnapshot`, danach `./gradlew generateFrontendApiTypes`),
steht in Abschnitt 4.

### Das `demo`-Objekt

Jede Antwort kann zusätzlich ein klar gekennzeichnetes `demo`-Objekt enthalten. Es ist **kein Teil
des produktiven Vertrags** und in einer echten Umgebung abgeschaltet. Es enthält:

- `accountId` und `personId`,
- `persons`: die Testpersonen des Personenverzeichnisses für die Auswahl „Testperson übernehmen“,
  samt E-Mail-Adresse, Mobilnummer und Freischaltcode. Das Personenverzeichnis enthält die
  Stammdaten der Versicherung; ein Freischaltcode ist ein Code, den die Versicherung per Brief
  schickt.
- `invitations`: die offenen Einladungen mit ihrem Einmalkennwort für die Auswahl „Einladung
  übernehmen“ ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)). Eine Einladung ist ein
  Brief, der eine Person zu einem bestimmten Vorgang einlädt.
- je nach Tool `tan` (die gerade ausgestellte TAN bzw. den Code) oder `password` (ein festes
  Demo-Passwort).

Tools liefern ihre Demo-Werte in einem eigenen Feld, `ToolOutcome.InProgress.demo`, getrennt von
`stepData`.

---

## 2) Für Tool-Entwickler

Von außen ist ein Tool eine Ressource unter `/tools/api/<toolId>/v<N>`. Angelegt wird sie bei allen
Tools auf dieselbe Weise. Was danach kommt, gestaltet jedes Tool selbst. Wie das Modul dahinter
gebaut ist, steht in [03-tool-architektur.md](03-tool-architektur.md).

### Pfade eines Tools

- Tool am Kanal anlegen: `POST /tools/api/{toolId}/v{N}?channel={channelSessionId}`. Die Antwort
  ist `201` mit `Location: /tools/api/{toolId}/v{N}/{toolSessionId}`. Die Anfrage hat meist keinen
  Inhalt, denn die `toolId` nennt schon die Art des Schritts und das Verfahren. Die Antwort enthält
  wie jede andere `channel`, `next` und `stepData`. `{N}` ist die Fassung, die der Kanal für dieses
  Tool angegeben hat. Eine andere Fassung lehnt der Orchestrator mit `409` ab.
- Tool fortschreiben und lesen: im Regelfall `PATCH`/`GET /tools/api/{toolId}/v{N}/{toolSessionId}`
- Zurück zur Auswahl: `POST /tools/api/{toolId}/v{N}/{toolSessionId}/back`
- Tool-Versuch verwerfen (Verfahren ablehnen): `DELETE /tools/api/{toolId}/v{N}/{toolSessionId}`

**Hinweis zur Umsetzung:** `POST`, `PATCH` und `GET` liegen für jedes Tool in einem eigenen
Controller, mit einem typisierten DTO für die Anfrage ([Tool-Architektur](03-tool-architektur.md)
Abschnitt 7). Nur `DELETE` ist davon ausgenommen.

### URL-Bereich der Tools

- Eine Tool-Sitzung legt immer der Orchestrator an, und zwar für alle Tools gleich. Nur dort
  entsteht die `toolSessionId`.
- Alles unterhalb von `/tools/api/{toolId}/v{N}/{toolSessionId}` gestaltet das Tool selbst: Es kann
  eigene Unterressourcen anlegen und die HTTP-Methoden frei wählen. `PATCH`/`GET` sind der
  Regelfall, aber keine Pflicht. Nicht jedes Verfahren passt zum Muster „Felder nachliefern",
  etwa WebAuthn oder eID.
- Bisher nutzt genau ein Tool diese Freiheit: `POST /tools/api/auth-kobil/v1/{toolSessionId}/pin-releases`
  gibt die KOBIL-PIN heraus, die das Backend verwahrt ([Verfahren `kobil`](verfahren/kobil.md)).
  Warum ist das kein zusätzliches Feld im `PATCH`?
  - Der PIN darf **nicht erneut abrufbar** sein. `stepData` wird aber bei jedem `GET` auf eine noch
    laufende Tool-Sitzung neu aufgebaut (`buildReadResponse`). Was dort steht, käme also bei jedem
    Lesen wieder heraus.
  - Eine Herausgabe erzeugt etwas Neues und ändert nichts Bestehendes. Sie ist einmalig, befristet
    und lässt sich nicht mit gleichem Ergebnis wiederholen.
  - Zwei verschiedene Vorgänge lassen sich mit einer eigenen URL sauberer trennen als über die
    Frage, welche optionalen Felder gerade gesetzt sind.

  Die Anfrage enthält ein typisiertes Entweder-oder: das Gerätegeheimnis **oder** das Passwort des
  Kontos. Gelingt das Entsperren, ist die Antwort `201` mit der PIN in `stepData`. Schlägt es fehl,
  ist sie `200` mit dem gewöhnlichen `stepData.error`. Denn ein falsches Geheimnis ist ein
  gewöhnlicher Fehlversuch und kein Fehlerstatus.
- Der garantierte Einstieg zum Fortsetzen ist **nicht** die Tool-Ressource, denn ihr `GET` darf ein
  Tool weglassen. Der garantierte Einstieg ist `GET /channels/{channelSessionId}`.

### Ein Tool-Durchlauf als Beispiel

Das Beispiel zeigt eine Registrierung. Der Nutzer identifiziert sich mit seinem Freischaltcode
(`ident-fsc`) und richtet danach SMS als Anmeldeverfahren ein (`enroll-sms`):

1. `POST /app/channels` mit
   `{"requiredAcr": "loa2", "availableTools": ["ident-fsc@1", "enroll-sms@1", ...]}`. Ohne `intent`
   gilt `fast_access`; `availableTools` ist Pflicht (siehe Abschnitt 3a). Die Antwort liefert eine
   neue `channelSessionId` und gleich den ersten Schritt:
   `next={"type":"tool","toolId":"ident-fsc","step":"input"}`. Weil es nur ein
   Identifizierungsverfahren gibt, entfällt die Auswahl. Weil es noch keine `ToolSession` gibt,
   fehlt auch die `toolSessionId`. Enthält `availableTools` zusätzlich `ident-eid`, liefert derselbe
   `POST` stattdessen eine Auswahl:
   `next={"type":"orchestrator","context":"registration","step":"selectIdentificationMethod"}`,
   `stepData={"kind":"select-method","options":["ident-fsc","ident-eid"]}`.
2. `POST .../tools/ident-fsc` (ohne Inhalt) legt die Tool-Ressource an. Die Antwort ist `201` mit
   `stepData={"kind":"missing-fields","missingFields":["kvnr","familyName","givenNames","birthDate"]}`
   und gesetzter `next.toolSessionId`. Nach dem Freischaltcode (`fsc`) fragt das Tool erst, wenn
   diese Angaben zum Personenverzeichnis passen.
3. `PATCH /tools/api/ident-fsc/v1/{toolSessionId}` mit den Feldern, zuletzt mit dem Freischaltcode.
   Solange Felder fehlen, kommt `200` mit aktualisiertem `stepData.missingFields`, und `next` zeigt
   weiter auf `ident-fsc`. Ist die Prüfung erfolgreich, lautet die Antwort:
   `stepData={"kind":"select-method","options":["enroll-sms"]}`,
   `next={"type":"orchestrator","context":"enrollment","step":"selectMethod"}`.
4. `POST .../tools/enroll-sms` liefert
   `stepData={"kind":"missing-fields","missingFields":["phoneNumber"]}`.
5. `PATCH .../enroll-sms` mit `{"phoneNumber": "..."}` löst den Versand der TAN aus. Die Antwort
   ist `stepData={"kind":"missing-fields","missingFields":["tan"]}`, dazu `demo={"tan":"123456"}`
   (siehe Abschnitt 1, „Das `demo`-Objekt“).
6. `PATCH .../enroll-sms` mit `{"tan": "123456"}` schließt ab:
   `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`. In derselben
   Antwort ist `channel.state` schon `"AUTHENTICATED"`; ein eigener `GET` ist nicht nötig.
7. `GET /channels/{channelSessionId}` liefert jederzeit den stabilen Zustand des Kanals. Das ist der
   garantierte Einstieg zum Fortsetzen, auch mitten in einem laufenden Tool samt dessen
   `toolSessionId`.

Jedes weitere Tool (`enroll-password`/`auth-password`, `confirm-email`, `enroll-email`/`auth-email`,
die `-lookup`-Varianten) folgt demselben Muster aus `POST`, `PATCH` und `GET`. Wo ein Tool davon
abweicht, steht auf der Seite seines Verfahrens (unten, „Besonderheiten einzelner Tools“).

### Zurück und Ablehnen

Einen Versuch eines Tools, der gestartet, aber noch nicht abgeschlossen ist, kann der Client auf
zwei Wegen verlassen (`LeaveToolController`). In beiden Fällen wird die `toolSessionId` sofort
ungültig. Keiner der beiden Wege startet ein anderes Tool. Das tut der Client selbst, sobald `next`
darauf zeigt.

- **Zurück** (`POST /tools/api/{toolId}/v{N}/{toolSessionId}/back`): Das Tool endet, ohne dass es
  als abgelehnt gilt. Die Journey zeigt wieder ihre Auswahlseite mit allen Verfahren, die sie gerade
  anbietet. Das verlassene Verfahren ist dabei. Die Auswahlseite erscheint auch dann, wenn nur ein
  Verfahren übrig ist, denn wer zurückgeht, will wählen und nicht sofort wieder im selben Tool
  landen. Die Strategie wird nicht gefragt, weil nichts passiert ist, das sie bewerten müsste.
  Manche Zustände haben keine Auswahlseite, etwa bei einem einzelnen bevorzugten Verfahren oder
  einer Zuordnung, die man überspringen kann. Dort wirkt Zurück wie Ablehnen. Im Web-Kanal löst
  jeder „Zurück“-Knopf einer Tool-Seite diesen Weg aus (`orchestrator_back`), in der App der
  „Zurück“-Knopf der Fußleiste.
- **Ablehnen** (`DELETE /tools/api/{toolId}/v{N}/{toolSessionId}`): Das Verfahren gilt in diesem
  Zustand als abgelehnt. Die Journey ermittelt die Kandidaten, also die Tools, die sie anbieten
  kann, neu. Das geschieht genauso wie nach dem letzten abgeschlossenen Tool (`Completed`). Wie es
  weitergeht, hängt von der Art des Zustands ab:
  - In einem Ausweichzustand geht es zum nächsten, etwas aufwendigeren Weg. Bleibt dabei nur ein
    Verfahren übrig, zeigt `next` direkt darauf.
  - In einem Pflichtzustand verlangt die Journey noch etwas, bevor sie weitergeht. Dort kommt die
    volle Auswahl zurück.

  Im Web-Kanal ist das der Knopf „Abbrechen“ auf den QR-Seiten (`orchestrator_abandon`), in der App
  der Ausweg, den ein Tool selbst anbietet (etwa „Jetzt nicht“ bei der Versichertennummer).

### Zusammenspiel von Prozess-API und Tool-Ressourcen

Ziel: Die fachliche Führung bleibt bei den Endpunkten für Kanal und Ablauf. Die App und Keycloak
nutzen für Eingabe- und Prüfschritte dieselben Tool-URLs, die für beide Kanäle gleich sind.

- Der Endpunkt des Kanals wählt über die `toolId` das Tool aus und legt eine technische
  `ToolSession` an. Fachliche Eingaben nimmt er dabei selbst nicht entgegen.
- Fachlich zuständig bleibt die Journey (`AuthJourney`) mit Intent, Zustand und Versuchsbudget,
  also der Zahl der erlaubten Fehlversuche. Die `ToolSession` hält nur Daten zu ihrem
  Lebenszyklus (`toolSessionId`, `journeyId`, Zeitstempel). Die `toolId` ergibt sich aus der Route,
  `stepData` aus den Daten des Moduls, und das Versuchsbudget gilt für die ganze Journey (siehe
  [Domänenmodell](02-domaenenmodell.md)).
- Die Antworten eines Ablaufs (`ChannelResponse`) nennen weder `accountId` noch `personId`. Es gibt
  zwei Ausnahmen: das `demo`-Objekt und im Web-Kanal `authData.subject`, über das Keycloak den
  Nutzer setzt. Ausdrücklich liefern diese Werte nur eigene Endpunkte:
  `GET /app/channels/device-link`, `GET .../idclaims` und die Kontoabfrage der Keycloak-Fassade
  (`/kc/accounts`).

Für Keycloak sind `auth-sms`, `auth-password` und `auth-email` (Login und Step-up) der einzige
Fall, den der App-Zugang nicht schon abdeckt. Anlegen, `PATCH` und `GET` laufen aber genau wie in
der App: `POST /tools/api/auth-sms/v1?channel={channelSessionId}`, danach
`PATCH`/`GET /tools/api/auth-sms/v1/{toolSessionId}` (Abschnitt 3b).

### Fassungen eines Tools

Ein Tool deklariert seine Fassungen im Modul. Für jede Fassung gibt es einen eigenen Controller
(Abschnitt 1, „Zwei Pfadräume und ihre Versionen“).

**Wann ein Tool eine neue Fassung braucht.** Nur dann, wenn der Server wissen muss, was der Client
kann. Die meisten Änderungen brauchen keine neue Fassung:

| Änderung | Neue Fassung? |
|---|---|
| Neues optionales Feld in Anfrage oder Antwort | nein |
| Neuer Aufruf, den alte Clients nicht brauchen (z. B. „Code erneut senden“) | nein |
| Neues **Pflichtfeld** in einer Anfrage (z. B. `consent` in `enroll-sms@2`) | ja |
| Neuer Aufruf oder Schritt, ohne den der Ablauf nicht endet | ja |
| Neue oder geänderte `StepData`-Form, die der Client darstellen muss | ja |
| Umbenanntes oder entfallenes Feld | ja |
| Neue Voraussetzung am Konto (`requires`, z. B. bestätigte E-Mail) | nein |

Eine neue Voraussetzung am Konto ändert den Vertrag nicht. Das Tool wird dann nur so lange nicht
angeboten, bis das Konto die Voraussetzung erfüllt, und das gilt für alle Clients gleich.

Zu jeder neuen Fassung gehört eine Entscheidung, was die alte Fassung ohne das Neue tut: einen
Ersatzwert setzen, weniger liefern oder abgeschaltet werden
([ADR-51](adr/ADR-051-versionen-als-pfadsegment.md)). Bis zum ersten Release legt man statt einer
neuen Fassung den eingefrorenen Stand neu fest (`publishApiVersion`, Abschnitt 4). Wie man eine
Fassung baut, zeigt [15-beispiel-neues-verfahren-backend.md](15-beispiel-neues-verfahren-backend.md)
Abschnitt 10. Das erste Tool mit zwei Fassungen ist `enroll-sms`
([Verfahren `sms`](verfahren/sms.md)).

### Besonderheiten einzelner Tools

Worin ein Tool vom Muster oben abweicht, steht auf der Seite seines Verfahrens
([Übersicht](verfahren/README.md)):

- `confirm-email`, `enroll-email`, `auth-email`: [verfahren/email.md](verfahren/email.md)
- `enroll-password`, `auth-password` und Keycloaks Passwortprüfung: [verfahren/password.md](verfahren/password.md)
- `ident-nect`, das die Anmeldung verlässt: [verfahren/nect.md](verfahren/nect.md)
- `auth-qr`/`auth-qr-lookup` (Warteseite) und `approve-qr`: [verfahren/qr.md](verfahren/qr.md)
- `auth-invite-lookup` (Einmalkennwort): [verfahren/invite.md](verfahren/invite.md)
- `auth-kobil` mit seiner Unterressource `pin-releases`: [verfahren/kobil.md](verfahren/kobil.md)

---

## 3) Für Orchestrator-Entwickler

Wer einen Kanal führt, ruft außer den Tool-Pfaden auch die Endpunkte des Kanals auf. Solche Clients
gibt es zwei: die App im App-Kanal (3a, der Orchestrator führt) und die Keycloak-Erweiterung im
Web-Kanal (3b, Keycloak führt). Beide nutzen dieselben Endpunkte des Kanals. Der Web-Kanal hat nur
zusätzlich einen eigenen Endpunkt zum Anlegen.

### 3a) App-Kanal

Alle Anfragen enthalten den Header `DPoP: <proof>`.

Die öffentliche App-API kennt nur die `channelSessionId`. Intern steht hinter dem Kanal die laufende
Journey (`AuthJourney`) mit einer eigenen `journeyId`. Diese Id dient dem Speichern, dem
Zusammenführen von Protokolleinträgen und der Revision. Der Client gibt sie nie vor.

#### Pfade

- Einstieg: `POST /orchestrator/api/v1/app/channels`. Die Antwort ist `201` mit
  `Location: .../channels/{channelSessionId}`. Es entsteht immer eine neue Ressource; ein
  bestehender Kanal wird nie fortgesetzt. Die Location zeigt auf die Kanal-Ressource, die für beide
  Zugänge gleich ist (Abschnitt 3b).
- Kanalzustand lesen: `GET /orchestrator/api/v1/channels/{channelSessionId}`
- Niveau anheben: `POST .../{channelSessionId}/step-ups` mit `{"requiredAcr": "..."}`. Das löst
  einen **Step-up** aus: Ein schon angemeldeter Nutzer beweist noch etwas, um ein höheres Niveau zu
  erreichen.
- Journey abbrechen: `DELETE .../{channelSessionId}/journey` (ohne Inhalt)
- Abmelden mit Bestätigung (startet eine Journey): `POST .../{channelSessionId}/logouts` (ohne
  Inhalt)
- Sofort abmelden: `DELETE .../{channelSessionId}` (ohne Inhalt)
- Eingerichtete Verfahren lesen: `GET .../{channelSessionId}/methods`
- Verfahren hinzufügen (startet das Einrichten): `POST .../{channelSessionId}/enrollments` (ohne
  Inhalt)
- Verfahren deaktivieren: `DELETE .../{channelSessionId}/methods/{methodInstanceId}` (ohne Inhalt).
  Der Eintrag wird über seine ID adressiert, nicht über den Namen des Verfahrens (siehe unten).
- Konto löschen (startet eine Journey): `POST .../{channelSessionId}/account-deletions` (ohne
  Inhalt)
- Rückfrage beantworten: `POST .../{channelSessionId}/answer` mit `{"answer": "accept"|"decline"}`.
  Das ist der gemeinsame Endpunkt für jede Rückfrage (`Prompt`, siehe unten).
- Tool-Katalog: `GET /orchestrator/api/v1/tools/catalog` (ohne DPoP und ohne Kanal). Er liefert je
  Tool `{toolId, method, role, versions}`. Daraus bildet der Client seine `availableTools`.
- Die Tools selbst: `POST /tools/api/{toolId}/v{N}?channel={channelSessionId}` und alles darunter
  (Abschnitt 2).

#### `POST /app/channels`: Parameter `intent`

`intent` ist optional; ohne Angabe gilt `fast_access`. Der Wert ist der Name eines
`AuthIntent`-Werts. Groß- und Kleinschreibung spielen keine Rolle (`AuthIntent.fromRequest`).
Unbekannte Werte lehnt der Orchestrator ab. Der Intent bestimmt, welcher Ablauf auf DIESEM Kanal
startet. Das gilt unabhängig davon, ob die Geräteverknüpfung (`DeviceAccountLink`) das Gerät
erkennt. Die Geräteverknüpfung speichert, welches Smartphone zu welchem Konto gehört.

- `fast_access` (Standard, auch ohne `intent`): Ist das Gerät schon mit einem Konto verknüpft
  (`DeviceAccountLink`), führt der Ablauf zur Anmeldung mit diesem bekannten Konto. Sonst startet die
  Sub-Journey `REGISTER`. Eine Sub-Journey ist eine Journey, die eine andere unterbricht und danach
  zu ihr zurückkehren kann.
- `lookup_login`: erzwingt die Anmeldung über die E-Mail-Adresse (E-Mail-Adresse plus Credential,
  siehe unten). Das gilt auch auf einem Gerät, das schon verknüpft ist. Die Verknüpfung wird für
  diesen Kanal nicht nachgeschlagen.
- `register`: erzwingt eine neue `REGISTER`-Journey, auch auf einem Gerät, das schon verknüpft ist.
  So lässt sich ein zweites Konto anlegen. Es kann sein, dass die neue Identifizierung zu einem
  anderen Konto führt als zu dem, mit dem das Gerät bisher verknüpft ist. Dann fragt der Kanal noch
  vor jeder Auswahl eines Verfahrens nach, ob die bestehende Geräteverknüpfung (`DeviceAccountLink`)
  ersetzt werden soll (`RegisterState.ConfirmDeviceRebind`).
  - Stimmt der Nutzer zu, wird das Gerät neu verknüpft. Das bisherige Geräte-Credential des alten
    Kontos (`enroll-device`) für genau diesen Schlüssel wird deaktiviert bzw. gelöscht.
  - Lehnt er ab, endet die Journey regulär, ohne Fehler, wie bei `DELETE .../journey`. Die alte
    Verknüpfung bleibt bestehen.
- `confirm_peer_login`: startet `AuthIntent.CONFIRM_PEER_LOGIN`. Damit bestätigt der Nutzer in der
  App einen Web-Login, der gerade wartet, oder lehnt ihn ab (`auth-qr`/`auth-qr-lookup`; siehe unten,
  „Peer-Login bestätigen"). Das geht auch von einem Kanal aus, der noch nicht angemeldet ist. Der
  Ablauf weicht aber nie auf Identifizierung oder Registrierung aus.

`requiredAcr` ist optional. Damit spart sich der Client den Umweg, erst auf einem niedrigen Niveau
einzusteigen und danach einen Step-up zu machen. Das Backend rechnet mit dem höheren Wert:
`max(Policy-Anforderung, Client-Wunsch)`.

`availableTools` ist Pflicht. Es gibt an, welche Tools dieser Client starten kann, jedes in genau
der einen Fassung, die er spricht: `["ident-fsc@1", "enroll-sms@1"]` (ADR-51). Ein Eintrag ohne
Fassung oder ein Tool in zwei Fassungen wird mit `400` abgelehnt. Die Menge gilt unverändert für die
ganze Lebensdauer des Kanals. Gespeichert wird nur, was der Server anbietet; ein unbekanntes Tool
oder eine Fassung, die der Server nicht anbietet, entfällt. Ein Tool außerhalb dieser Menge bietet
der Orchestrator nie an, und er lehnt es auch bei direktem Aufruf ab. Dasselbe gilt für einen
Aufruf in einer anderen Fassung ([03-tool-architektur.md](03-tool-architektur.md) Abschnitt 5,
Verfügbarkeit).

Zusätzlich kann der Betreiber zur Laufzeit eingreifen:

- Er kann jede Fassung eines Tools je Kanaltyp sperren
  (`PUT .../tools/{tool}/availability/{APP|WEB}` mit `{tool}` wie `enroll-sms@2`).
- Er kann je Kanaltyp festlegen, in welcher Reihenfolge die Tools angeboten werden. Diese
  Reihenfolge gilt je Tool für alle Fassungen (`PUT .../tools/order/{APP|WEB}`).
- Den aktuellen Stand liefert `GET /orchestrator/admin/tools/availability`.

Ein Kanal, der die gesperrte Fassung angegeben hat, bekommt das Tool nicht mehr angeboten. Ein Kanal
mit einer anderen Fassung desselben Tools bekommt es weiterhin. Das alles sind Betriebsendpunkte und
nicht Teil des App-Vertrags (ADR-32, ADR-51).

#### `GET /channels/{channelSessionId}`

Dieser Aufruf liest den stabilen Zustand des Kanals. Neben `state` enthält der `channel`-Block zwei
weitere Felder:

- `currentAmr`: was **diese Sitzung** schon nachgewiesen hat. Der Wert stammt aus der
  `SessionEvidence` der Sitzung, also aus ihren gesammelten Nachweisen.
- `activeMethods`: alle eingerichteten Verfahren des Kontos, als Objekte
  `{id, method, label, factorTypes, maxAcr, enrolledUnderAcr, effectiveAcr}`. Sie stehen dort
  unabhängig davon, was diese Sitzung geprüft hat. Dazu gilt:
  - `fsc` ist nie dabei. Eine Identifizierung steht nämlich im Protokoll `account.change_log`
    (IDENTIFIED), nicht in `account.auth_method`.
  - `id` adressiert den Eintrag für `DELETE`.
  - `label` ist nur bei Verfahren gesetzt, die mehrere Einträge haben können (derzeit `device` und
    `kobil`).
  - `auth-device` wird zum Anmelden nur auf dem Gerät angeboten, das den passenden Schlüssel hat
    (`docs/04-orchestrierung.md`). Deaktivieren lässt sich das Verfahren von jedem Gerät aus.

Beide Felder werden erst gefüllt, sobald auf diesem Kanal ein Faktor nachgewiesen ist
(`hasProvenFactor`). Ein Gerät, das nur wiedererkannt wurde, bekommt sie nicht.

`next` ist immer gesetzt, auch wenn die Journey abgeschlossen ist
(`{"type":"orchestrator","context":"authentication","step":"authenticated"}`). Ein eigenes Feld
`stepUpRequired` gibt es nicht. Nur bei `LOGGED_OUT` (Endzustand) fehlt `next` ganz.

#### `GET /app/channels/device-link`

Dieser Aufruf liest nur. Er sagt, ob dieses Gerät schon mit einem Konto verknüpft ist
(`DeviceAccountLink`, [Domänenmodell](02-domaenenmodell.md) Abschnitt 1). Das Gerät erkennt der
Orchestrator am DPoP-Proof; eine `channelSessionId` ist nicht nötig. Der Aufruf legt **weder** Kanal
noch Journey an. Die Antwort ist `{"linked": true, "accountId": 42}` bzw. `{"linked": false}`.

Einen Namen nennt die Antwort bewusst nicht. Wer den Geräteschlüssel hat, hat damit noch keinen
Faktor bewiesen, und ein gestohlenes Gerät soll nicht verraten, wem es gehört. Den Namen liefern
erst nach der Anmeldung die ID-Token-Claims.

Dazu kommt das Demo-Feld `boundCredentials`. Es enthält je einen Eintrag `{method, reference}` für
jedes Credential des verknüpften Kontos, das an einen Schlüssel gebunden ist und auf **diesem**
Schlüssel liegt. Das Verfahren `device` nennt dort seinen Credential-Schlüssel, `kobil` die
Kennung, die der Anbieter diesem Telefon gegeben hat. Was angezeigt wird, entscheidet jedes Modul
selbst: Es meldet beim Einrichten eine `reference`
([03-tool-architektur.md](03-tool-architektur.md) Abschnitt 5). Der Orchestrator kennt dafür keinen
einzigen Verfahrensnamen.

Ein fehlender Eintrag ist dabei genauso aussagekräftig wie ein vorhandener. Hat ein Client lokale
Daten zu einem Verfahren, das hier nicht mehr steht, sind diese Daten veraltet. Genau daran erkennt
das KOBIL-Frontend, dass es sein Gerätegeheimnis löschen muss ([09-dpop.md](09-dpop.md)
Abschnitt 3).

#### `POST /channels/{channelSessionId}/step-ups`: Step-up-Auslöser

Dieser Aufruf hebt die geforderte Untergrenze des Kanals an, also das Niveau, unter das der Kanal
nicht fallen darf. Im Web-Zugang ist es derselbe Endpunkt (Abschnitt 3b). Die Anfrage lautet
`{"requiredAcr": "loa3"}`. Was dann geschieht, hängt vom Kanal ab:

- Reicht das aktuelle Niveau nicht, startet das Backend eine Journey `AuthJourney(STEP_UP)` und
  liefert den fälligen Schritt als `ChannelResponse`.
- Reicht es schon, zeigt `next` sofort auf `authenticated`.
- Ist der Kanal noch nicht angemeldet, startet kein Step-up. Dann muss die laufende Anmeldung oder
  Registrierung das neue Niveau erreichen, und die Antwort zeigt ihren aktuellen Schritt.

Das Niveau lässt sich nur anheben; ein niedrigeres `requiredAcr` wird ignoriert.

Es kann sein, dass kein vorhandenes Verfahren das geforderte Niveau erreicht, eine erneute
Identifizierung (`ident-fsc`/`ident-eid`) es aber allein erreichen könnte. Dann fragt die Journey
zuerst per `stepData.prompt` nach (`context: "prompt", step: "confirm"`). Stimmt der Nutzer zu,
folgt die Auswahl. Lehnt er ab, endet der Step-up ohne Fehler. Nur wenn auch das nicht möglich ist,
bricht die Journey mit `410` ab ([Orchestrierung](04-orchestrierung.md)).

#### Abbruch

`DELETE .../journey` bricht die laufende Journey (`AuthJourney`) ab und setzt den Zustand des
Kanals (`ChannelSession.state`) zurück ([Domänenmodell](02-domaenenmodell.md) Abschnitt 3). Danach
startet der Kanal **denselben Intent** neu, mit dem er eröffnet wurde. Bricht man einen Step-up
(`STEP_UP`) oder die Verwaltung der Verfahren (`MANAGE_AUTH_METHODS`) ab, lautet die Antwort direkt
`authenticated`. Die Zuordnung zum Konto und die Token-Sitzung der App (`AppTokenSession`) leitet
der Orchestrator über die Geräteverknüpfung (`DeviceAccountLink`) neu ab. Ein Konto, das zuvor per
`ident-fsc` angelegt wurde, bleibt bestehen.

Im Web-Kanal ruft „Abbrechen“ auf der Verfahrensauswahl der Keycloak-Anmeldeseite dieses `DELETE`
**nicht** auf. Stattdessen beendet es den ganzen Login bei Keycloak (`context.cancelLogin()` in
`OrchestratorAuthenticator`). Keycloak kehrt dann mit `error=access_denied` zur Website zurück, und
die Website zeigt „Anmeldung abgebrochen“ an. Der Grund: Würde derselbe Intent neu starten, käme der
Nutzer wieder auf dieselbe Seite, und eine Registrierung würde endlos von vorn beginnen. Der
verlassene Web-Kanal läuft nach seiner Lebensdauer von selbst ab.

#### Logout

Es gibt zwei Varianten:

- **Abmelden mit Bestätigung** (bevorzugt): `POST .../{channelSessionId}/logouts` startet eine
  `LOGOUT`-Journey mit einer Rückfrage ([Orchestrierung](04-orchestrierung.md) Abschnitt 3). Stimmt
  der Nutzer über `POST .../answer` zu, wird der Kanal `LOGGED_OUT`.
- **Sofort abmelden** (für Clients ohne Rückfrage): `DELETE /channels/{channelSessionId}` beendet
  den Kanal direkt (`AUTHENTICATED → LOGGED_OUT`, Endzustand, `204`). Ein laufender Ablauf wird
  dabei abgebrochen, und die `AppTokenSession` wird verworfen.

Die Geräteverknüpfung bleibt nutzbar (`DeviceAccountLink`, [DPoP-Bindung](09-dpop.md) Abschnitt 3).

Beide Abmeldewege, die bestätigte Abmeldung (`POST .../logouts`) und die direkte (`DELETE`), laufen
über dieselbe Funktion (`JourneyService.endSession`). Sie verwirft die Tokens und beendet die
Keycloak-Sitzung dieses Kanals.

#### AccessToken (`GET .../{channelSessionId}/token`)

**Nur im `APP`-Kanal** (ADR-9): Ein `WEB`-Kanal hat nie eine `AppTokenSession` und braucht auch
keine. `ChannelService.getToken` prüft deshalb zuerst den Kanaltyp und weist einen `WEB`-Kanal mit
`409 INVALID_STATE_TRANSITION` ab. Für `APP` entscheidet der `TokenProvider` je nach Profil, woher
das Token kommt:

- **Profil `keycloak`** (`KeycloakTokenProvider`): Er liefert ein echtes AccessToken, das Keycloak
  signiert hat. Es enthält echte Claims `acr` und `amr` (über `OrchestratorAcrAmrMapper`, wie im
  Web-Kanal). Claims sind die einzelnen Angaben in einem Token: `acr` nennt das Niveau, `amr` die
  Verfahren. Es gibt vier Fälle, aufgebaut wie `TokenService.tokenFor`:
  1. Das Token ist noch lange genug gültig: Der Orchestrator gibt es unverändert zurück.
  2. Es läuft bald ab, und ACR und AMR sind unverändert: Der Orchestrator erneuert es über Keycloaks
     `refresh_token`-Grant. Ein Grant ist eine festgelegte Art, bei Keycloak ein Token anzufordern.
  3. Die Anmeldung hat noch keine Keycloak-Sitzung. Das ist beim ersten Token so, beim Übergang
     nach `AUTHENTICATED`. Dann ruft der Orchestrator einen eigenen OAuth2-Grant auf
     (`urn:identity-demo:account-token`, `AccountTokenGrantType` in `keycloak-extension`, ADR-9),
     mit `account_id`, `acr` und `amr` als Parametern. Der Grant öffnet die Sitzung. Ihre Id
     (`sid`) speichert die `AppTokenSession` als `keycloakSessionId`.
  4. Ein Step-up hat die zwischengespeicherten Tokens verworfen: Der Orchestrator ruft denselben
     Grant auf, zusätzlich mit `session_id`. Der Grant setzt genau diese Sitzung fort und schreibt
     das neue `acr`/`amr` hinein. Ist die Sitzung nicht mehr gültig, lehnt er ab, statt eine neue
     zu öffnen.

  Den Grant darf nur der vertrauliche Client des Orchestrators aufrufen (`orchestrator-app-token`,
  Client-Attribut `identity-demo.account-token-grant`, Anmeldung per `private_key_jwt`). Jeden
  anderen Client, auch den öffentlichen Browser-Client, weist er mit `unauthorized_client` ab. Bei
  `acr` und `amr` prüft der Grant nur die Form: `acr` muss `loa1`, `loa2` oder `loa3` sein, und
  jeder `amr`-Wert muss auf `[a-z0-9_-]+` passen. Sonst antwortet er mit `invalid_grant`. Lehnt er
  eine `session_id` ab, steht das im Keycloak-Ereignis, nicht in der Fehlerantwort. Jede Anmeldung,
  also jeder App-Kanal, hat ihre eigene Keycloak-Sitzung. Das gilt auch, wenn mehrere Geräte zum
  selben Konto gehören
  ([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)).
- **Standardprofil** (`MockTokenProvider`), also der Orchestrator ohne Keycloak: Er liefert das
  Mock-JWT aus `TokenService` (`alg=none`, `iss=mock-keycloak`).

**Die Sitzung entsteht mit der Anmeldung.** Das erste Token holt nicht der Client. Es entsteht
schon beim Übergang nach `AUTHENTICATED` (`JourneyService.finish` → `AppTokenIssuer`). Lehnt
Keycloak die Sitzung ab, wird der Kanal nicht `AUTHENTICATED`. Dann ist die Antwort auf den letzten
Schritt `409 INVALID_STATE_TRANSITION`, der Schritt wird zurückgerollt, und der Nutzer wählt das
Verfahren noch einmal. Ist die Sitzung beim Abschluss eines Step-ups schon abgelaufen, endet der
Kanal als `EXPIRED`.

**Die Frist folgt der Sitzung.** Ab `AUTHENTICATED` setzt jedes Token die Ablaufzeit des Kanals
(`expiresAt`) auf das Sitzungsfenster, das der Token-Dienst meldet. Das ist Keycloaks
`refresh_expires_in`, also das Minimum aus SSO idle (Grenze für Leerlauf) und dem Rest von SSO max
(Höchstdauer der Sitzung). Ist das Fenster vorbei, weist der Orchestrator den Kanal ab wie jeden
abgelaufenen Kanal (`404`). Die Frist von 24 Stunden gilt nur bis zur Anmeldung.

Solange der Kanal angemeldet ist, erneuert auch jede Interaktion mit seiner Journey das Token per
Refresh, sobald ein Viertel des Fensters verbraucht ist. Als Interaktion zählen: starten, Tool
aktivieren, Tool-Schritt, zurück, anderes Verfahren, Antwort. Das verlängert Keycloaks
Leerlauf-Fenster und damit auch die Frist des Kanals. Lehnt Keycloak ab, endet der Kanal als
`EXPIRED`, und die Interaktion bekommt `410 PROCESS_GONE`.

`minValiditySeconds` wirkt in beiden Profilen gleich. Es gibt eine Ausnahme: Ein Step-up, der die
gesammelten Nachweise der Sitzung (`SessionEvidence`) verändert
(`SessionEvidenceService.applyEvidence`/`applyEvidenceUpdate`), verwirft das zwischengespeicherte
Token ausdrücklich. Die Sitzung und ihr Fenster bleiben dabei erhalten.

**Ablauf der Anmeldung**: In zwei Fällen entsteht nie eine neue Sitzung: wenn das Sitzungsfenster
abgelaufen ist, und wenn Keycloak eine Erneuerung oder Fortsetzung ablehnt, weil die Sitzung
beendet ist. Die Anmeldung ist dann vorbei. Der Kanal endet als `EXPIRED`, seine Tokens werden
verworfen, und die Antwort ist `410 PROCESS_GONE` („bitte neu anmelden“). Im Profil `keycloak`
gelten Keycloaks eigene Grenzen (SSO idle/max). Im Standardprofil übernimmt `TokenService` die
Aufgabe von Keycloak: Er vergibt beim ersten Token eine eigene Sitzungs-Id, und jede Erneuerung
verschiebt das Fenster um 30 Minuten. Er wirkt also als Grenze für Leerlauf.

Meldet Keycloak, dass diese Sitzung abgemeldet wurde (`POST .../kc/accounts/{accountId}/sign-outs`,
Abschnitt 3b), endet der App-Kanal ebenfalls als `LOGGED_OUT`.

#### ID-Token-Claims (`GET .../{channelSessionId}/idclaims`)

**Nur im `APP`-Kanal**, wie das AccessToken oben. Es gilt dieselbe Vorbedingung, und ein
`WEB`-Kanal bekommt ebenso `409 INVALID_STATE_TRANSITION`.

Der Aufruf liefert die fachlichen Claims. Sie sind nicht in die Signatur des AccessTokens
eingebaut. Es sind diese: `sub`/`accountId`/`personId`/`versnr`, `acr`/`amr`, `auth_time`,
`email`/`email_verified` und `name`.

`personId` ist die Partnernummer, unter der das Personenverzeichnis eine Person führt (`P` und neun
Ziffern, ADR-34). `versnr` ist die Mitgliedsnummer. Beide liest der Orchestrator bei jedem Aufruf
neu aus dem Personenverzeichnis.

`email` und `email_verified` beschreiben das angemeldete Konto. `email` ist die Adresse, für die der
Inhaber mit `confirm-email` bewiesen hat, dass er sie kontrolliert. Nur für diese Adresse ist
`email_verified` wahr. Keycloak sucht Nutzer über dieselbe Adresse. Deshalb stehen die Kontaktdaten
des Personenverzeichnisses nie unter `email` oder `phone_number`, auch nicht in Keycloak. Braucht
eine Anwendung sie, bekäme sie einen eigenen Claim unter einem eigenen Scope, bei jedem Aufruf neu
gelesen wie `versnr`. Für eine Mobilnummer gälte dieselbe Regel. Heute steht keine Mobilnummer im
Token, weil sie zum SMS-Verfahren gehört und nicht zum Konto
([Tool-Architektur](03-tool-architektur.md) Abschnitt 4).

`name` ist die einzige Stelle, an der das Frontend erfährt, WER angemeldet ist. Was darin steht,
entscheidet der Anker `PERSON_ID`. Ein Anker ist eine Angabe, über die sich ein Konto eindeutig
wiederfinden lässt:

- Ist `personId` vorhanden, ist `name` „Vorname Name" der Person (`PersonDirectory.displayName`).
- Fehlt `personId`, gehört das Konto einem Interessenten, also niemandem, der einer Person im
  Personenverzeichnis zugeordnet ist (ADR-10/18). Dann nimmt `name` die eigenen bestätigten Claims
  des Kontos, und zwar den stärksten noch gültigen `FAMILY_NAME`-/`GIVEN_NAMES`-Claim. `null` ist
  `name` nur, wenn es auch davon keinen gibt.

Aus `personId` und `versnr` leitet das Frontend die Rolle ab (ADR-34):

- mit `versnr`: Versicherter,
- nur mit `personId`: Partner,
- mit keinem von beiden: Interessent.

Ein eigener Claim für die Rolle würde dieselben Werte nur doppelt ausdrücken. Für den Aufruf gilt
derselbe `channelAccessGuard` wie überall sonst. Die Seite nach erfolgreicher Anmeldung zeigt Name
und Rolle knapp im Begrüßungstext: „Angemeldet als *Name* (Versicherter/Partner/Interessent)". Die
vollständigen Claims lassen sich aufklappen, wie die Details des AccessTokens.

#### Verfahren verwalten (AuthIntent.MANAGE_AUTH_METHODS)

Hier verwaltet der Nutzer freiwillig die Anmeldeverfahren seines Kontos, auf einem Kanal, der
bereits `AUTHENTICATED` ist. Das ist unabhängig von `REGISTER` und `STEP_UP`
([Orchestrierung](04-orchestrierung.md) Abschnitt 3). Im Web-Kanal ist der Einstieg eine Required
Action von Keycloak (Abschnitt 3b).

- `GET .../methods` liest die aktiven Verfahren als eigene Liste
  (`{"methods": [{"id","method","label",…}]}`). Es sind dieselben Daten wie in
  `ChannelResponse.activeMethods`, also nie mit `fsc`. Solange auf diesem Kanal noch kein Faktor
  nachgewiesen ist (`hasProvenFactor`), kommt eine leere Liste statt eines Fehlers. Ein Gerät, das
  nur wiedererkannt wurde, bekommt also nichts.
- `POST .../enrollments` bietet dieselben Kandidaten zum Einrichten an wie `REGISTER` und startet
  das Einrichten. Gibt es nichts mehr einzurichten, ist das kein Fehler: Die Antwort ist `200`, und
  `next` zeigt ohne `stepData` direkt auf `authenticated`.
- `DELETE .../methods/{methodInstanceId}` widerruft einen aktiven *Eintrag* eines Verfahrens. Dabei
  wird die Credential-Zeile in dem Modul gelöscht, dem sie gehört (`EnrollmentCleanup`). Der
  Eintrag selbst bleibt als deaktiviert gespeichert. Adressiert wird er über die `id` aus
  `GET .../methods`, nie über den Namen des Verfahrens. Denn ein Verfahren kann mehrere aktive
  Einträge haben ([03-tool-architektur.md](03-tool-architektur.md) Abschnitt 5,
  `allowsMultipleInstances`). Die Antwort ist `409`, wenn das Konto danach das `requiredAcr` des
  Kanals nicht mehr erreichen könnte. Sonst könnte sich jemand selbst aussperren. Das Widerrufen
  ist nicht auf Einträge des aufrufenden Geräts beschränkt.
- `POST .../methods/{methodInstanceId}/changes` ändert einen aktiven Eintrag direkt. Dazu läuft
  das `enroll-*`-Tool des Verfahrens noch einmal. Sobald es fertig ist, ersetzt der neue Eintrag
  den alten (mit neuer `id`). Bis dahin gilt der alte weiter, auch wenn der Nutzer abbricht. Möglich
  ist das nur für Einträge mit `changeable: true` in `GET .../methods` (`sms`, `password`). Für
  andere Einträge ist die Antwort `409`, für einen nicht aktiven Eintrag `404`. Das Tool zeigt in
  `stepData` an, dass es einen Eintrag ersetzt
  (`{"kind":"enroll-password","missingFields":["password"],"replaces":true}`).
- `DELETE .../attributes/{attribute}` nimmt ein **Attribut des Kontos** zurück statt eines
  Credentials. Das ist nur die bestätigte E-Mail-Adresse (`email`). Welches Attribut der Inhaber
  selbst zurücknehmen darf, legt `AnchorRule.retractableByHolder` fest. Identitätsanker
  (`person_id`, `member_number`, die Karten-Pseudonyme) gehören nicht dazu; sie lehnt der
  Orchestrator mit 409 ab.

  Der Aufruf ist das Gegenstück zu `DELETE .../methods/{id}` und durchläuft dieselbe Prüfung. Der
  Unterschied liegt in den Folgen: Jedes Credential, das dieses Attribut per `requires` verlangt
  hat, wird mit entzogen, und zwar über alle Ebenen hinweg. Nimmt der Nutzer seine Adresse zurück,
  verliert er also auch ein Passwort, das darauf eingerichtet ist (`enroll-password` verlangt
  `ClaimRequirement(EMAIL, PROVEN)`), und alles, was wiederum dieses Passwort voraussetzt (ADR-24).
  Die Antwort ist `409`, wenn genau diese Folgen das Konto unter das `requiredAcr` des Kanals senken
  würden. Die Meldung nennt dann, was dabei zusätzlich entfernt würde. Zurücknehmen lassen sich nur
  Attribute, die dem Konto selbst gehören. Ein Stammdatenfeld gehört nicht uns, sondern dem
  Personenverzeichnis, und ein Attribut eines Verfahrens verschwindet mit diesem Verfahren.
- Vier dieser Aufrufe ändern etwas: `POST .../enrollments`,
  `POST .../methods/{methodInstanceId}/changes`, `DELETE .../methods/{methodInstanceId}` und
  `DELETE .../attributes/{attribute}`. Sie verlangen zusätzlich, dass die aktuelle Sitzung eine
  Mindestschwelle erreicht hat (`selfServiceAcrFloor`): loa2, für ein nie identifiziertes Konto
  loa1. Das Ändern eines Eintrags verlangt außerdem mindestens das Niveau, unter dem der Eintrag
  eingerichtet wurde. Reicht das nicht, enthält die Antwort statt der Aktion einen Step-up-Schritt.
  Danach ruft der Client den Endpunkt erneut auf.
- Dieselben vier Aufrufe verlangen außerdem einen frischen Nachweis. Ist der jüngste Nachweis der
  Sitzung älter als fünf Minuten (`identity.policy.self-service-max-age`), enthält die Antwort statt
  der Aktion eine erneute Bestätigung. Dafür genügt ein **beliebiges** aktives `auth-*`-Verfahren.
  Gibt es mehrere, kommt eine Auswahl mit `next={"context":"auth","step":"selectMethod"}`. Ist der
  Nachweis erbracht, führt der Orchestrator die verlangte Aktion aus; ein erneuter Aufruf ist nicht
  nötig. Der Nachweis gilt nur für diese eine Aktion. `POST .../enrollments` fragt nicht nach, wenn
  es nichts mehr einzurichten gibt.

#### Das `Prompt`-Objekt

Manche Zustände einer Journey (`JourneyState`) warten nicht auf ein Tool, sondern auf eine ausdrückliche Antwort mit
Ja oder Nein. Jeder solche Zustand (`AnswerableState`) enthält in `stepData` einen `prompt` (Form
`confirm`):
`{"kind": "Confirm", "title": "...", "description": "...", "confirmLabel": "...", "cancelLabel": "...", "destructive": true|false}`.

Für **jeden** `AnswerableState` hat `next` denselben festen Wert, egal zu welchem Intent:
`{"type":"orchestrator","context":"prompt","step":"confirm"}`. Beantwortet wird jeder `Prompt` über
denselben gemeinsamen Endpunkt `POST .../{channelSessionId}/answer` mit
`{"answer": "accept"|"decline"}`.

Den ganzen Text jeder Rückfrage liefert **das Backend**; der Client formuliert nie selbst. So
braucht eine geänderte Rückfrage keine neue App-Version.

`Prompt` ist ein `sealed interface` mit `kind` als Unterscheidungsmerkmal. Die einzige Variante ist
`Confirm`. Eine weitere Variante wäre denkbar, etwa eine Auswahl unter mehreren Antworten, ist aber
nicht angelegt. Verwendet wird `Prompt` für:

- die Frage nach der Geräteverknüpfung bei der Anmeldung über die E-Mail-Adresse,
- die Bestätigung beim Löschen des Kontos,
- die Bestätigung beim Abmelden,
- `ReIdentifyState.OfferReIdent` vor der Sub-Journey `RE_IDENTIFY`
  ([Orchestrierung](04-orchestrierung.md)).

#### Konto löschen (AuthIntent.DELETE_ACCOUNT)

Hier löscht der Nutzer sein eigenes Konto, auf einem Kanal, der bereits `AUTHENTICATED` ist. Die
Ja/Nein-Bestätigung kommt **immer zuerst und ohne Bedingung**. Erst nach der Zustimmung prüft der
Orchestrator dieselbe Schwelle `selfServiceAcrFloor` wie bei `MANAGE_AUTH_METHODS`: loa2, für ein
nie identifiziertes Konto nur loa1 ([Orchestrierung](04-orchestrierung.md) Abschnitt 3). Der Ablauf:

1. `POST .../{channelSessionId}/account-deletions` (ohne Inhalt) startet die Journey und liefert
   sofort die Rückfrage: `next={"type":"orchestrator","context":"prompt","step":"confirm"}`, dazu
   `stepData.prompt` mit `destructive: true`.
2. `POST .../answer` mit `{"answer":"accept"}`. (Mit `"decline"` bricht der Ablauf ab wie bei einem
   gewöhnlichen Abbruch, zurück auf `AUTHENTICATED`.) Erst jetzt prüft der Orchestrator die
   Schwelle. Reicht das aktuelle Niveau nicht, liefert die Antwort einen Step-up-Schritt. Danach
   ruft der Client `account-deletions` erneut auf.
3. Reichte das Niveau schon vorher, kommt es auf das Alter des jüngsten Nachweises der Sitzung an.
   Ist er älter als fünf Minuten (`identity.policy.self-service-max-age`), folgt ein frischer
   Nachweis über ein **beliebiges** aktives `auth-*`-Verfahren des Kontos, egal welches Niveau es
   erreicht. Anders als bei `STEP_UP` zählt dabei auch ein Faktor erneut, der schon nachgewiesen
   ist. Genau ein Verfahren genügt. Bei mehreren kommt dieselbe Auswahlseite
   `next={"context":"auth","step":"selectMethod"}`. Ist der jüngste Nachweis jünger, wird sofort
   gelöscht. **Ausnahme**: Musste in Schritt 2 erst ein Step-up stattfinden, zählt dessen Nachweis
   bereits als der hier geforderte.
4. Nach erfolgreichem Nachweis wird das Konto unwiderruflich gelöscht, mit allem, was nur ihm
   gehört:
   - alle Credential-Datensätze der Tool-Module, auf die seine Verfahren verweisen (aktive **und**
     abgelöste),
   - der `DeviceAccountLink`,
   - jede `AppTokenSession`,
   - die Zeile in `account` selbst.

   Die `person` im Personenverzeichnis (`personenverzeichnis`) bleibt unberührt
   ([Tool-Architektur](03-tool-architektur.md) Abschnitt 7, `EnrollmentCleanup`).
5. Jede `ChannelSession`, die je an dieses Konto gebunden war, setzt der Server auf `LOGGED_OUT`.
   Ein anderes angemeldetes Gerät braucht danach einen neuen `POST /channels`.
6. Nach dem erfolgreichen Abschluss lautet die Antwort `channel.state="LOGGED_OUT"` ohne `next`. Das
   ist dieselbe Form wie bei einem normalen Logout.

#### Anmeldung über die E-Mail-Adresse (`auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup`, „Login ohne DPoP")

Diese Tools erreicht man nur über `POST /channels` mit `intent: "lookup_login"`, nie über die
normale Auswahl der Kandidaten. Sie haben die Rolle `ToolRole.ACCOUNT_LOOKUP_AUTH`, und
`AuthPolicy.authCandidates` wählt ausschließlich Tools mit `KNOWN_ACCOUNT_AUTH`. Diese Tools finden
das Konto selbst, über die eingegebene E-Mail-Adresse:

- `auth-sms-lookup` und `auth-email-lookup` arbeiten mit zwei `PATCH`-Aufrufen. Der erste enthält
  `{"email": "..."}`. Er findet das Konto und verschickt bei Erfolg die TAN bzw. den Code. Der zweite
  enthält `{"tan"/"code": "..."}`.
- `auth-password-lookup` erwartet `{"email": "...", "password": "..."}` in einem einzigen `PATCH`.
- Schutz vor dem Ausforschen von Adressen: Eine unbekannte oder unbestätigte E-Mail-Adresse verhält
  sich in Form und Antwortzeit genauso wie ein gefundenes Konto mit falschem Credential.
- Bei Erfolg ist der Ablauf nicht immer sofort zu Ende:
  - Ist dieses Gerät noch keinem Konto oder schon demselben Konto zugeordnet, bietet der
    Orchestrator die Geräteverknüpfung optional an.
  - Ist es mit einem anderen Konto verknüpft, fragt er ausdrücklich nach, bevor er diese
    Verknüpfung in `DeviceAccountLink` überschreibt. Lehnt der Nutzer ab, endet der Login trotzdem
    erfolgreich, nur ohne neue Verknüpfung. Stimmt er zu, wird das Gerät neu verknüpft.
- In der Demo ist `demo.password` ein fester Wert, unabhängig vom gefundenen Konto. Die
  E-Mail-Adresse kommt aus der Auswahl der Testperson (`demo.persons`).

#### Peer-Login bestätigen (AuthIntent.CONFIRM_PEER_LOGIN)

Beim QR-Login meldet sich der Nutzer auf der Website mit Hilfe der App an: Der Browser zeigt einen
QR-Code, und die App gibt den Login frei. Dabei bestätigt ein App-Kanal einen wartenden Web-Login
oder lehnt ihn ab. Angestoßen hat diesen Web-Login ein `auth-qr` bzw. `auth-qr-lookup` im Web-Kanal
([Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`). Es gibt zwei gleichwertige Einstiege:

- `POST /app/channels` mit `{"intent":"confirm_peer_login"}`: Einstieg ohne bestehende Sitzung,
  siehe Parameter `intent` oben.
- `POST /channels/{channelSessionId}/peer-logins` (ohne Inhalt): auf einem Kanal, der bereits
  `AUTHENTICATED` ist.

Beide durchlaufen dieselbe Prüfung:

1. Über die Geräteverknüpfung (`DeviceAccountLink`) ist kein Konto bekannt (das ist nur ohne
   bestehende Sitzung möglich): Die Antwort ist `410`. Der Ablauf weicht nie auf Identifizierung
   oder Registrierung aus.
2. Das aktuelle Niveau liegt unter `loa2`: Es folgt ein Step-up-Schritt. Danach ruft der Client den
   Einstiegs-Endpunkt erneut auf.
3. Das Niveau ist schon `loa2`, aber der jüngste Nachweis der Sitzung ist älter als fünf Minuten:
   Die Antwort verlangt einen frischen Nachweis über ein beliebiges aktives `auth-*`-Verfahren, wie
   beim Löschen des Kontos in Schritt 3 oben (`next={"context":"auth","step":"selectMethod"}` bei
   mehreren Kandidaten). Ist der Nachweis jünger, folgt sofort Schritt 4. **Ausnahme**: Musste in
   Schritt 2 erst ein Step-up stattfinden, zählt dessen Nachweis bereits als der geforderte.
4. `approve-qr` wird gestartet. Die Aufrufe dieses Tools und die Gegenseite im Browser
   (`auth-qr`/`auth-qr-lookup`) stehen auf der Seite des Verfahrens:
   [verfahren/qr.md](verfahren/qr.md).

### 3b) Web-Kanal

Im Web-Kanal spricht der Browser nie mit dem Orchestrator. Mit dem Orchestrator spricht
ausschließlich Keycloak, und zwar direkt von Server zu Server. Dafür nutzt Keycloak seine
Java-Erweiterung in `keycloak-extension/`, die über Keycloaks Erweiterungsschnittstelle (SPI)
eingebunden ist.

#### Die Keycloak-Fassade: Kanal anlegen und fortsetzen

Für diesen Zugang gibt es einen einzigen eigenen Endpunkt. Er legt einen Kanal an oder aktualisiert
ihn:

- `PATCH /orchestrator/api/v1/kc/channels/{channelSessionId}`: Beim ersten Aufruf legt er den Kanal
  unter der ID an, die Keycloak gewählt hat. Bei jedem weiteren Aufruf setzt er ihn fort. Die ID
  stammt aus Keycloaks laufendem Anmeldeablauf (`AuthenticationSessionModel`/`UserSessionModel`).

Inhalt der Anfrage (`KeycloakChannelUpsertRequest`, alle Felder optional):

- **`subject`**: Wem dieser Durchlauf in Keycloak gehört, das sogenannte Subjekt. Das ist entweder
  ein Konto (`{"type":"account","id":"42"}`) oder eine Einladung (`{"type":"invitation","id":"…"}`,
  [ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)). Die Form ist dieselbe wie bei
  `authData.subject` in der Antwort. Es gilt:
  - Ein Konto ordnet einen Kanal ohne Subjekt sofort diesem Konto zu.
  - Eine Einladung ordnet einen Kanal nur über ihren eigenen Nachweis zu. Gehört der Kanal ihr
    nicht schon, lehnt der Orchestrator mit `409` ab, denn ein Vorgangszugang wird nicht
    aufgewertet (ADR-48, Nachtrag K-5). Ein Vorgangszugang ist die Anmeldung mit dem
    Einmalkennwort einer Einladung; er gilt nur für einen Vorgang.
  - Ist der Kanal schon einem anderen Subjekt zugeordnet, also einem anderen Konto, einer Einladung
    statt eines Kontos oder umgekehrt, antwortet der Orchestrator mit `409` und ändert nichts.
- **`targetAcr`**: Das Niveau (LoA), das Keycloak anfragt, bereits übersetzt in einen ACR-Wert des
  Orchestrators. Es hebt die Untergrenze des Kanals nur an, nie ab. Außerdem filtert es die
  Kandidaten von `WEB_SELECT_METHOD` ([Orchestrierung](04-orchestrierung.md) Abschnitt 3). Ein
  unbekannter Wert führt zu `400`, bevor sich am Kanal etwas ändert. Er wird nie stillschweigend als
  `none` behandelt.
- **`restoreData` / `kcSessionId`**: `restoreData` ist ein signiertes Token aus
  `GET .../restore-data`. Es stammt von einer FRÜHEREN, unabhängigen `ChannelSession` derselben
  Keycloak-Nutzersitzung. Damit gibt Keycloak die Nachweise, die dort erbracht wurden, samt ihrem
  Zeitpunkt an einen neu angelegten Kanal weiter. Für Niveaus über `loa1` zählen sie nur
  30 Minuten ([Orchestrierung](04-orchestrierung.md) Abschnitt 4). `kcSessionId` bindet das Token
  an Keycloaks dauerhafte Nutzersitzung (`UserSessionModel`). Ein falsches, abgelaufenes oder
  manipuliertes Token behandelt der Orchestrator als `null`, nie als Fehler.
- **`availableTools`**: Welche `toolId`s das Keycloak-Theme, also die Gestaltung der
  Anmeldeseiten, darstellen kann. Dafür gibt es je Tool einen `WebToolRenderer`. Der Orchestrator
  liest das Feld nur beim ersten Aufruf. Es ist das Gegenstück zu `availableTools` bei
  `POST /app/channels`. Wie dort speichert der Kanal nur `toolId`s, die der Katalog kennt.
- **`intent`**: Wird nur beim ersten Aufruf gelesen. Fehlt er, gilt `web_select_method`. Erlaubt
  sind nur `web_select_method` und `register`. Einen unbekannten oder unzulässigen Wert lehnt der
  Orchestrator ab (`409`).

`GET .../{channelSessionId}/restore-data?kcSessionId=...` gibt es nur für einen einzigen Aufruf:
den, den Keycloak am Ende des Anmeldeablaufs macht. Er liefert die gesammelten Nachweise dieses
Kanals als Token, gebunden an diese `kcSessionId` (`RestoreDataCodec`). Keycloak speichert das Token
als Notiz im `UserSessionModel`. Bei einem SPÄTEREN Step-up schickt Keycloak es unverändert als
`restoreData` im ersten `PATCH` zurück.

Dieselbe Antwort enthält `sessionExpiresAt` (in Epochensekunden). Das ist das späteste Ende dieser
Keycloak-Sitzung, wenn keine weitere Aktivität folgt. Es wird aus SSO idle und SSO max des Realms
berechnet (`SessionEnd`); ein Realm ist ein abgeschlossener Bereich in Keycloak mit eigenen Nutzern
und Einstellungen. Der Orchestrator setzt die Frist des Kanals auf den kleineren der beiden Werte:
seine 30 Minuten oder diesen Zeitpunkt. So besteht auch der Web-Kanal nie länger als seine Sitzung
([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)). Ein Einladungs-Kanal
gibt bei `restore-data` nichts zurück ([Verfahren `invite`](verfahren/invite.md)).

Danach läuft **alles** über dieselben Endpunkte wie im App-Zugang, ohne das Präfix `/kc/`:

- `GET .../channels/{channelSessionId}`, `.../step-ups`, `.../journey`, `.../methods`,
  `.../enrollments`, `.../token`, `.../idclaims` (Abschnitt 3a)
- `POST /tools/api/{toolId}/v{N}?channel={channelSessionId}` (Anlegen), danach `PATCH`/`GET
  /tools/api/{toolId}/v{N}/{toolSessionId}`, genau wie im App-Zugang

#### Peer-Auth

Peer-Auth bedeutet: Keycloak und Orchestrator weisen sich gegenseitig aus. Keycloak weist sich
dabei nicht mit einem DPoP-Proof aus, sondern mit einer signierten Peer-Auth-Assertion im Header
`Authorization` (kein mTLS, ADR-7). Die Assertion ist ein JWT, das Keycloak für jede Anfrage neu
erstellt. Es enthält:

- `iss`/`aud` aus dem Parametersatz (`peerAuthIssuer`/`peerAuthAudience`, standardmäßig
  `identity-demo-keycloak`/`identity-demo-orchestrator`), also wer das JWT ausstellt und für wen es
  bestimmt ist,
- `htm`/`htu` dieser Anfrage, also ihre HTTP-Methode und ihre URL,
- `jti` und `iat`, also eine eindeutige Kennung und den Zeitpunkt der Ausstellung. Damit schützt
  sich der Orchestrator gegen wiederholt eingespielte Anfragen. Er nutzt dafür denselben
  Zwischenspeicher wie bei DPoP ([09-dpop.md](09-dpop.md)), aber mit einem eigenen Namensraum `kc:`
  und einem eigenen Zeitfenster (`keycloak.peer-auth.max-clock-skew-seconds`/`max-age-seconds`, im
  Profil `keycloak` je 300 Sekunden),
- die Kanalbindung dieses Anmeldedurchlaufs (Claim `channel_binding`). Die Kanalbindung ordnet die
  Anfrage einem bestimmten Kanal zu; `KeycloakChannelAccessGuard` prüft sie gegen diesen Kanal. Bei
  den Aufrufen ohne Kanal (unten, „Endpunkte unter `/kc/`“) enthält `channel_binding` stattdessen
  die `accountId` bzw. die Id der Einladung und wird gegen den Pfadparameter geprüft.

Die Signatur prüft der Orchestrator gegen Keycloaks JWKS, also die Liste der öffentlichen Schlüssel
von Keycloak. Es gibt ein Schlüsselpaar je Client, nicht je Nutzer. Der Orchestrator hält das JWKS
im Zwischenspeicher (`KeycloakJwkSource`, standardmäßig 600 Sekunden,
`keycloak.peer-auth.jwks-cache-ttl-seconds`). Nennt eine Assertion eine unbekannte Schlüssel-ID
(`kid`), holt er das JWKS einmal neu, denn Keycloak kann den Schlüssel gewechselt haben. Ein
Neustart ist dafür nicht nötig. Das Neuholen geschieht aber höchstens alle 30 Sekunden. So kann
niemand mit erfundenen `kid`s erreichen, dass jede Anfrage einen Abruf bei Keycloak auslöst.

Umgekehrt holt die Keycloak-Erweiterung das JWKS, mit dem der Orchestrator seine Antworten
signiert, nur einmal je Orchestrator und nicht bei jedem Aufruf. Auch sie hält es im
Zwischenspeicher (`OrchestratorResponseVerifier`, Nimbus `JWKSourceBuilder` mit Wiederholung).

#### `authData`

Jede Antwort an einen `WEB`-Kanal enthält zusätzlich `authData` mit `subject`, `acr` und `amr`. Bei
`APP` fehlt es immer. Keycloaks `OrchestratorAuthenticator` schreibt es sofort in seine
Session-Notes, also in die Notizen seiner Anmeldesitzung.

`subject` nennt, wer angemeldet ist: `{"type": "account", "id": "42"}` für ein Konto oder
`{"type": "invitation", "id": "<Hash>"}` für eine Einladung nach einem Einmalkennwort
([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)). Danach setzt Keycloak den Nutzer aus
der passenden Federation. Eine Federation ist in Keycloak eine Quelle für Nutzer außerhalb von
Keycloak. Hier gibt es eine für Konten und eine für Einladungen, jede mit einer eigenen festen UUID
als Komponenten-Id. Keycloak lässt nie zu, dass ein Subjekt die Sitzung eines anderen fortsetzt
(`LoginCompletion`).

`amr` ordnet jedem Verfahren seine Quelle zu: `"orchestrator"` für ein abgeschlossenes Tool des
Orchestrators in diesem Kanal, `"kc"` für einen Nachweis, den Keycloaks Sitzung aus einem früheren
Durchlauf weitergibt (RestoreData). Keycloak selbst prüft kein Verfahren
([ADR-58](adr/ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md)). Das ist nur eine Information. Den
kombinierten `acr` bestimmt ausschließlich der Orchestrator.

#### Welche Tools der Web-Kanal anbietet

Der Web-Kanal kennt kein Gerät. Eine Geräteverknüpfung (`DeviceAccountLink`) gibt es nur im
App-Kanal ([02-domaenenmodell.md](02-domaenenmodell.md)). Angemeldet wird über den Login per
E-Mail-Adresse oder über den eigenen Einstiegs-Intent `WEB_SELECT_METHOD`
([04-orchestrierung.md](04-orchestrierung.md) Abschnitt 3). Registriert wird über `REGISTER`
(`intent=register`, siehe oben). `ident-fsc`, `ident-eid`, `confirm-email` und die `enroll-*`-Tools
werden über dieselben `WebToolRenderer` angezeigt. Ohne Konto bietet die Auswahl neben den
Lookup-Anmeldungen auch das Einmalkennwort an (`auth-invite-lookup`,
[Verfahren `invite`](verfahren/invite.md)).

Eine Lücke wird hier ausdrücklich benannt: **`enroll-kobil`/`auth-kobil` und
`enroll-device`/`auth-device` haben keinen `WebToolRenderer`** und fehlen damit im Web-Kanal. KOBIL
braucht ein Telefon, der Geräteschlüssel den Schlüsselspeicher des Telefons. Von einer
Anmeldeseite, die der Server erzeugt, ist keines davon erreichbar. Das Theme nennt diese `toolId`s
deshalb nicht in `availableTools`, und so werden sie dort nie angeboten. Derselbe Mechanismus, der
alte App-Versionen lauffähig hält, deckt also auch diesen Fall ab.

Manche Tools schicken den Nutzer für einen Schritt aus der Anmeldung heraus zu einem anderen
Dienst. Ein solches Tool kehrt danach auf die Action-URL des laufenden Keycloak-Schritts zurück. So
macht es `ident-nect` ([Verfahren `nect`](verfahren/nect.md)).

#### Die QR-Warteseite: Statusabfrage in Keycloak

An genau einer Stelle fragt der Browser im Web-Zugang etwas außerhalb eines Formulars ab. Auch diese
Abfrage geht an Keycloak, nicht an den Orchestrator
([ADR-45](adr/ADR-045-qr-warteseite-fragt-im-hintergrund.md)). Wie die Seite damit arbeitet, steht
in [verfahren/qr.md](verfahren/qr.md).

- `GET /realms/{realm}/orchestrator-qr/status?client_id=…&tab_id=…`. Die Adresse steht fertig im
  Seitenattribut `statusUrl` von `tool-qr-wait`. Das Login-Theme ruft sie alle zwei Sekunden auf.
- **Wer eine Antwort bekommt:** nur der Browser, der das Cookie `AUTH_SESSION_ID` dieser Anmeldung
  hat. Keycloak findet den Durchlauf darüber genauso wie für seine eigenen Seiten. Fehlt etwas
  davon, kommt `404` ohne Inhalt. CORS-Header gibt es nicht.
- **Antwort:** `{"state":"waiting"}` oder `{"state":"ready"}`, mit `Cache-Control: no-store`.
  `waiting` heißt: Der Orchestrator nennt beim `GET /tools/api/{toolId}/v{N}/{toolSessionId}` genau
  diese Tool-Sitzung im Schritt `waitForApp`. Alles andere ist `ready`, auch ein Fehler beim Lesen.
  Die Seite schickt ihr Formular dann einmal ab, und Keycloak zeigt, wie es weitergeht.
- **Nur lesen:** Der Endpunkt ändert weder den Anmeldeablauf noch die Journey.

#### Anmeldeverfahren verwalten im Web-Kanal (Keycloak Required Action)

Die Verwaltung der Verfahren (`AuthIntent.MANAGE_AUTH_METHODS`) funktioniert für beide Zugänge
gleich (Abschnitt 3a, „Verfahren verwalten"; `POST .../enrollments` nutzt denselben
`DpopBindingKeyResolver`). Der Web-Kanal braucht deshalb **keinen eigenen Endpunkt im
Orchestrator**, nur einen eigenen Einstieg. Dieser Einstieg ist eine Required Action von Keycloak
(`RequiredAction`), also ein Schritt, den Keycloak einem angemeldeten Nutzer vorschalten kann. Ihre
Einstellungen sind `getId()="orchestrator-manage-methods"` und `defaultAction=false`. Sie wird also
nie erzwungen und lässt sich nur über `kc_action` auslösen. Sie ist im Ablauf `orchestrator-browser`
registriert. Man erreicht sie über dieselbe `/auth`-URL wie einen normalen Login, ergänzt um
`kc_action=orchestrator-manage-methods`.

Ein zweiter Login wird nicht erzwungen. Der vorangehende Durchlauf von `orchestrator-browser` nutzt
das bestehende SSO-Cookie von Keycloak. `OrchestratorResumeAuthenticator` bringt dann den neuen
Kanal im Orchestrator über `restoreData` auf `AUTHENTICATED`, sofern die Nachweise ausreichen.
Sonst gilt der normale Weg über Login und Step-up.

Wiederhergestellte Nachweise behalten ihren Zeitpunkt. Die Liste der Verfahren erscheint deshalb
ohne Nachfrage. „Hinzufügen", „Ändern" und „Entfernen" verlangen aber eine erneute Bestätigung,
sobald der letzte Nachweis älter als fünf Minuten ist. „Ändern" steht nur an Einträgen mit
`changeable` und ruft `POST .../methods/{id}/changes` auf. Die Seiten von `enroll-password` und
`enroll-sms` lesen `stepData.replaces` und weisen darauf hin, dass der bisherige Eintrag ersetzt
wird.

Endet der Ablauf erfolgreich, ruft die Required Action `startEnrollments(...)` auf dem neuen Kanal
auf. Sie zeigt `next` über dieselbe Zuordnung zu den `WebToolRenderer`n an. Im Frontend baut
`redirectToManageMethods()` (`webOidc.ts`) dieselbe `/auth`-URL wie `redirectToLogin`. Zurück geht
es über den bestehenden Weg `completeLoginIfRedirected()`.

Wie `next` gedeutet wird (Auswahlseite, Formular eines Tools oder automatischer Start eines Tools),
ist **gemeinsamer Code** von `OrchestratorAuthenticator` und dieser Required Action
(`OrchestratorNextDispatch.classify`/`dispatchToolAction`, `keycloak-extension`). Nur die Reaktion
darauf (`context.success()`/`failure()` bzw. die Entsprechungen von `RequiredActionContext`) ist für
jeden Aufrufer eigen.

#### Keycloak liest die Konten – keine Spiegelung

Keycloak hält keine Kopie der Konten. Stattdessen liest seine Nutzer-Federation
(`OrchestratorStorageProvider`, ohne Import) ein Konto bei Bedarf beim Orchestrator nach
(`KeycloakAccountLookupController`). Das geht nach Konto-Id, nach exakter E-Mail-Adresse oder nach
Benutzername. Jede Abfrage ist ein einzelner Zugriff, entweder über den Primärschlüssel oder über
den eindeutigen E-Mail-Anker. Eine Liste aller Konten gibt es nicht. Die Suche der Admin-Konsole
findet nur exakte Treffer ([ADR-38](adr/ADR-038-keycloak-liest-konten.md)).

- **Was Keycloak zeigt:** den Benutzernamen (die bestätigte E-Mail-Adresse, sonst `account-<id>`),
  die E-Mail-Adresse, Vor- und Nachname und die Attribute hinter den Token-Claims (`personId`,
  `kvnr`, `versnr`, `birthDate`, `streetAddress`, `postalCode`, `locality`; im Token heißen sie
  `birth_date`, `street_address`, `postal_code`, `locality`). Für ein Konto mit Person gelten nur
  die Werte des Personenverzeichnisses. Für einen Interessenten gilt der stärkste bestätigte Wert
  aus dem Konto. Ein Konto ohne beides zeigt Platzhalternamen. Alle diese Werte sind in Keycloak
  schreibgeschützt.
- **Was in welchem Token steht:** Das AccessToken geht an jeden Fachdienst, den App oder Website
  aufrufen. Deshalb enthält es von den Attributen des Nutzers nur die beiden, die seine Identität
  bezeugen: `person_id` (die Partnernummer) und `versnr` (die Mitgliedsnummer). Dazu kommen die
  Angaben über die Anmeldung selbst: `sub`, `acr`, `amr`, `auth_time`, `orchestrator_account_id`
  und beim Vorgangszugang `process` und `invitation`. Alle übrigen Attribute stehen nur im ID-Token
  und in `userinfo`: Name, Benutzername, E-Mail-Adresse, KVNR, Geburtsdatum und Anschrift.
  Festgelegt ist das in der Migration V8.
- **Aktualität:** Keycloak hält einen föderierten Nutzer, also einen aus der Federation gelesenen,
  höchstens 60 Sekunden im Cache (Migration V2). Eine geänderte Adresse oder ein geänderter Name
  ist spätestens dann sichtbar.
- **Nutzer-Id und `sub`:** `f:<UUID>:<accountId>`. Die Komponenten-Id ist eine feste UUID
  (`USER_STORAGE_COMPONENT_ID`). Eine zufällig neu erzeugte Id würde jedes `sub` ändern.
- **Was Keycloak selbst hält:** Sitzungen, Zustimmungen und sonstige föderierte Daten eines
  Nutzers. Fehlversuche zählt Keycloak nicht. Er prüft selbst kein Geheimnis, und sein
  Brute-Force-Schutz ist aus ([ADR-58](adr/ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md)).
  Fehlversuche zählt allein die Kontosperre des Orchestrators (ADR-44).
- **Konto gelöscht:** `KeycloakAccountRemovalListener` löscht genau diese Daten, die Keycloak selbst
  hält (`DELETE /admin/realms/{realm}/orchestrator-accounts/{accountId}`, `AccountRemoval`). Das ist
  das einzige Ereignis eines Kontos, das Keycloak gemeldet wird. Eine Änderung am Konto braucht
  keinen Aufruf: Was ein Übergang einer Journey am Konto ändert, sieht Keycloak beim nächsten Lesen,
  ohne dass ihm jemand etwas meldet. Dasselbe gilt für eine Änderung im Personenverzeichnis, die
  ganz ohne Journey abläuft (`PersonChanged` → Konto, `applyDirectoryChange`). Auch dann liest
  Keycloak die neuen Werte beim nächsten Mal (ADR-34).
- **Einladungen** ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)): Eine zweite
  Nutzer-Federation (`InvitationStorageProvider`, feste UUID `INVITATION_STORAGE_COMPONENT_ID`,
  Migration V6) liest Einladungen des Personenverzeichnisses als eigene Nutzer. Sie liest nur per
  Id (`KeycloakInvitationLookupController`), und der Cache gilt ebenfalls 60 Sekunden. Nutzer-Id
  und `sub` sind `f:<UUID der Einladungs-Federation>:<Id der Einladung>`. Der Nutzer enthält die
  Stammdaten der Person und die Attribute `orchestratorInvitation` und `orchestratorProcess`
  (Claims `invitation` und `process`). Er ist nur aktiviert, solange die Einladung offen ist.
  Meldet das Verzeichnis, dass eine Einladung beendet ist (`InvitationEnded`), meldet
  `KeycloakInvitationLogoutListener` den Nutzer ab
  (`POST /admin/realms/{realm}/users/{id}/logout`). Das läuft über dieselbe Registry wie die
  Löschung. Ein Einladungs-Nutzer hat außer seinen Sitzungen keine Keycloak-Daten, die zu löschen
  wären.

Die Löschung und das Ende einer Einladung erreichen Keycloak über die Event Publication Registry
von Spring Modulith. Das ist ein Mechanismus, der Ereignisse speichert, bis sie zugestellt sind. Wie
das im Betrieb aussieht und wie man offene Zustellungen findet, steht in [07-betrieb.md](07-betrieb.md)
Abschnitt 3a.

#### Endpunkte unter `/kc/`

Was nur Keycloak aufruft, liegt unter `/orchestrator/api/v1/kc/`. Damit gehört es nicht zum
eingefrorenen Vertrag (ADR-50). Außer der Fassade oben (`PATCH .../kc/channels/{channelSessionId}`,
`GET .../restore-data`) sind das Aufrufe ohne Kanal und ohne ToolSession. Sie betreffen ein Konto
oder eine Einladung, die Keycloak schon kennt:

- Die Kontoabfrage (`/kc/accounts`, [ADR-38](adr/ADR-038-keycloak-liest-konten.md)) und der
  Einladungs-Nutzer (`GET /orchestrator/api/v1/kc/invitations/{invitation}`,
  [Verfahren `invite`](verfahren/invite.md)).
- `POST /orchestrator/api/v1/kc/accounts/{accountId}/sign-outs?kcSessionId=…`: Keycloak meldet
  einen Logout für das Anmeldeprotokoll (ADR-39, Nachtrag). Das Anmeldeprotokoll hält fest, wann
  sich jemand an- und abgemeldet hat. Die Antwort ist `204`. Den Logout im Web-Kanal führt Keycloak
  allein durch. Sein Event-Listener `orchestrator-sign-in-log` ruft diesen Endpunkt nach dem Commit
  auf und wartet nicht auf das Ergebnis. Kanäle, die zu dieser Keycloak-Sitzung gehörten, enden
  damit, im Web-Kanal wie im App-Kanal (ADR-43). `channel_binding` ist hier die `accountId`.
- `POST /orchestrator/api/v1/kc/invitations/{invitation}/sign-outs?kcSessionId=…`: dasselbe für
  einen Vorgangszugang (ADR-48). Die Web-Kanäle dieser Keycloak-Sitzung enden, und das
  Anmeldeprotokoll bekommt eine Zeile für die Einladung. Die Antwort ist `204`. `channel_binding`
  ist die Id der Einladung.

**Offen:** Noch nicht entschieden ist, wie der Logout im Web-Kanal funktionieren soll. Die Frage
ist, ob ein Client `DELETE /channels/{id}` für `WEB`-Kanäle selbst aufrufen darf oder ob nur
Keycloak den Logout auslöst.

---

## 4) Weitere Endpunkte und der Vertrag im Detail

### Betriebs- und Demo-Endpunkte

Welche Endpunkte unter `/orchestrator/admin` und `/orchestrator/demo` liegen und warum sie nicht zum
App-Vertrag gehören, steht in Abschnitt 1, „Der Vertrag: `api/`“. Wie man Tools je Kanaltyp sperrt
und ihre Reihenfolge festlegt, beschreibt Abschnitt 3a (`availableTools`).

**Aktive Sitzungen und Zurücksetzen.** `GET …/admin/sessions` und `GET …/demo/sessions` liefern
denselben Bericht (`ActiveSessions`). Er hat zwei Teile:

- **Orchestrator:** die lebenden Kanäle (`ChannelSession`s). Das sind alle, die weder `LOGGED_OUT`
  noch `EXPIRED` sind und deren `expiresAt` in der Zukunft liegt. Sie werden je Kanaltyp gezählt
  (`APP`, `WEB` = Website). Dazu kommen die zehn neuesten mit Zustand, Konto und Anzeigename aus dem
  Personenverzeichnis.
- **Keycloak** (nur mit Profil `keycloak`): die offenen Sitzungen des Browser-Clients und des
  App-Token-Clients. Der Orchestrator liest sie über die Admin-API von Keycloak, als
  `orchestrator-migration`. Je Client stehen dort die Anzahl und die zehn neuesten Sitzungen, jeweils
  mit dem Kanal, der dazugehört. Im App-Kanal findet der Orchestrator ihn über
  `AppTokenSession.keycloakSessionId`, auf der Website über
  `ChannelSession.durableKeycloakSessionId`. Ist Keycloak nicht erreichbar, steht das in
  `keycloak.error`; der Rest des Berichts kommt trotzdem.

`POST …/admin/demo-reset` und `POST …/demo/reset` setzen die Demo auf dieselbe Weise zurück
(`DemoReset`). Das geschieht in drei Schritten:

1. Alle Konten werden gelöscht, genauso wie beim Löschen eines einzelnen Kontos. Das meldet die
   Kanäle dieser Konten ab und löscht in Keycloak ihre Sitzungen ([07-betrieb.md](07-betrieb.md)
   Abschnitt 3a).
2. Danach wird jeder noch aktive Kanal beendet, auch einer ohne Konto, etwa eine laufende
   Registrierung. Zurücksetzen heißt: Niemand ist danach noch mitten in einem Vorgang.
3. Zuletzt werden in Keycloak alle abgemeldet, damit auch keine Sitzungen früherer Läufe übrig
   bleiben.

Die Antwort nennt beides (`deletedAccounts`, `endedSessions`). Danach gelten wieder die
Voreinstellungen für Verfahren und Registrierungsreihenfolge.

**Journey-Trace.** Der Journey-Trace hält jeden Schritt einer Journey fest. Er ist eine Ansicht zur
Fehlersuche und für die Demo und wird 14 Tage aufbewahrt ([Betrieb](07-betrieb.md) Abschnitt 3).
Ein Revisionsprotokoll ist er nicht; das ist `account.change_log`. Der Journey-Trace gehört nicht
zum App-Vertrag. Es gibt ihn nur als Betriebsendpunkt `GET /orchestrator/admin/journey-trace`
(hinter der Admin-Anmeldung, über alle Konten und Kanäle).

### Stellvertreter externer Systeme

Einzelne Verfahren arbeiten mit Fremdsystemen, also mit Systemen außerhalb des Orchestrators. In
der Demo sind diese durch Stellvertreter unter `/mock-*` ersetzt (`/mock-kobil`,
`/mock-personenverzeichnis`, `/mock-nect`, ADR-31). Sie gehören nicht zum App-Vertrag
(Abschnitt 1). Jeder Stellvertreter liefert seine eigenen Texte auf dieselbe Weise wie der
Orchestrator: `GET /mock-*/texts/{lang}` (`/mock-kobil`, `/mock-personenverzeichnis`,
`/mock-nect`), ebenfalls mit ETag und 304.

### Wie der Vertrag entsteht und geprüft wird

`OpenApiSnapshotTest` zerlegt den Vertrag in die Teile unter `api/contract/` (`ContractSplit`). In
der CI vergleicht `checkPublishedApiCompatibility` jeden Teil mit seinem eingefrorenen Stand unter
`api/published/` (mit openapi-diff). Bei einer inkompatiblen Änderung schlägt die Prüfung fehl. Die
Meldung nennt dann den Umschlag oder das Tool mit Fassung (`enroll-sms@1`). Eine neue oder
entfallene Fassung erzeugt nur einen Hinweis.

Einen bewusst neuen Stand übernimmt man mit `./gradlew publishApiVersion`. Im PR zeigt dann der
Diff unter `api/published/`, dass ein veröffentlichter Stand geändert wird. Die Prüfung hat eine
Grenze: openapi-diff hält ein entfallenes optionales Feld einer Anfrage für kompatibel. Ist das in
Wahrheit eine Umbenennung, muss das Review sie erkennen.

Beide YAML-Dateien entstehen im selben Testlauf aus derselben laufenden Anwendung
(`OpenApiSnapshotTest`). Deshalb können sie nicht voneinander abweichen. Die Moduldateien sind
keine zweite Quelle, sondern ein Ausschnitt. Eine Änderung an einem SMS-Endpunkt steht so in
`api/modules/auth_sms.yaml` (knapp 500 Zeilen) und nicht irgendwo in über 5000 Zeilen. Welche
Gruppen es gibt, leitet `ModuleApiGroups` aus den vorhandenen `@RestController` ab, nicht aus einer
gepflegten Liste.

Der Snapshot sichert nur, dass die Datei zu den Annotationen passt. Er sichert nicht, dass der
Server auch sendet, was dort steht. Deshalb läuft jede Anfrage der Integrationstests durch
`ContractStatusCheck`: Liefert der Server einen Erfolgsstatus (2xx), den `api/openapi.yaml` für die
Operation nicht deklariert, scheitert der Test. Fehlerstatus deklariert der Vertrag nicht je
Operation. Sie folgen dem allgemeinen Fehlervertrag (`ErrorResponse`).

Manche Schemas nutzt mehr als ein Modul, und sie stehen im App-Vertrag: das Antwortformat
`ChannelResponse` mit allem, was dazugehört, und `ErrorResponse`. Solche Schemas stehen nur in
`api/openapi.yaml`. Die Moduldateien verweisen mit `../openapi.yaml#/components/schemas/…` darauf.
Sonst enthielte jede Moduldatei dieselben rund 360 Zeilen, und eine Änderung am Antwortformat
erschiene als zwölf Diffs. Auch diese Aufteilung ergibt sich von selbst und nicht aus einer Liste:
Was nur ein Modul nutzt, bleibt in dessen Datei. Der Nachteil: `StepData` zeigt in der Moduldatei
alle möglichen Formen, nicht nur die dieses Moduls.

`api/openapi.yaml` bleibt trotzdem eine einzige Datei. Ein aufgeteilter Vertrag, der die
Moduldateien per `$ref` einbindet, ist nicht brauchbar. Unter OpenAPI 3.1 setzt swagger-parser
externe Verweise direkt ein. Dann verlieren beide Generatoren alle Modellnamen und die Zuordnung der
Unterscheidungsmerkmale (Diskriminator-Mapping). Außerdem vergleicht openapi-diff aufgeteilte
Dateien nicht verlässlich.

Was sonst noch dazugehört:

- **Der Test vergleicht, er beschreibt nicht.** Ändert sich eine Antwort, ohne dass die Snapshots
  aktualisiert sind, schlägt er fehl. Die Änderung übernimmt man mit
  `./gradlew updateOpenApiSnapshot` und danach `./gradlew generateFrontendApiTypes`.
- **`frontend/src/types.ts` leitet ab, statt nachzubauen.** Von Hand steht dort nur, was das
  Backend als offene Map liefert: die benannten Werte im `demo`-Block und die Formen der Tokens.
  Dazu kommt eine Ergänzung zur erzeugten Menge der `stepData`-Formen: `UnknownStepData` steht für
  eine Form, die dieser Build noch nicht kennt, und `stepDataOf(stepData, kind)` ist der einzige
  Weg, eine Form zu lesen.
- **Pflichtfelder stehen im Schema.** springdoc übernimmt die Nicht-null-Typen von Kotlin nicht von
  selbst. Das erledigt `KotlinRequiredModelConverter`: Eine Property, die nicht `null` sein kann
  und keinen Standardwert hat, wird `required`.
- **YAML statt JSON**, weil die Dateien in Diffs gelesen werden: keine Anführungszeichen, keine
  Klammern, und lange Beschreibungen stehen als umbrochener Text statt in einer endlosen Zeile.
