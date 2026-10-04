# Idee: Orchestrator und Tools einzeln versionieren

Status: **Konzept, nicht umgesetzt** (Stand 2026-10-04, Issue `DPoP-demo-7luc`). Das Dokument
beschreibt, wie der Orchestrator und jedes Tool eine eigene Version bekommen, beide über denselben
Mechanismus: ein Segment im Pfad. Es baut auf
[ADR-50](../adr/ADR-050-api-versionierung-umschlag-und-tool.md) auf: Der Vertrag ist schon in
Umschlag und Tools geteilt und je Tool eingefroren, eine zweite Fassung desselben Tools lässt das
Modell aber noch nicht zu.

---

## 1) Ausgangslage und Annahmen

- Die App ist veröffentlicht. Alte App-Versionen bleiben im Einsatz, der Server kann sie nicht
  aktualisieren.
- **Eine App beherrscht je Tool genau eine Fassung.** Verschiedene App-Versionen beherrschen
  verschiedene Fassungen; der Server muss deshalb mehrere Fassungen eines Tools zugleich führen.
- Ein Tool muss sich so ändern, dass eine alte App es nicht mehr bedienen kann: Es braucht ein
  neues Pflichtfeld oder einen neuen Aufruf, ohne den der Ablauf nicht endet.
- Alte und neue App sollen beide weiter funktionieren. Später fällt die alte Fassung weg.
- Der Web-Kanal ist nicht betroffen: Die Keycloak-Erweiterung wird mit dem Server ausgeliefert und
  spricht immer die neueste Fassung.

Was es heute schon gibt:

- `/orchestrator/api/v1` trägt eine Version im Pfad. Sie gilt für alles darunter, auch für die
  Tools.
- `availableTools` ist Pflicht. Der Kanal merkt sich, welche toolIds der Client bedienen kann; ein
  anderes Tool wird nie angeboten.
- `tool_availability` sperrt ein Tool je Kanaltyp zur Laufzeit (ADR-32).
- `checkPublishedApiCompatibility` hält jede Tool-Datei gegen ihren eingefrorenen Stand. Ein neues
  oder entfallenes Tool ist ein Hinweis, kein Bruch.

Was fehlt: Die Tool-Pfade hängen am Präfix des Orchestrators und haben keine eigene Version. Ein
Tool gibt es je Methode und Rolle genau einmal, mit einem Controller und einer Vertragsdatei.

## 2) Wann es überhaupt eine neue Fassung braucht

Die meisten Änderungen brauchen keine. Eine Fassung ist nur nötig, wenn der Server wissen muss,
was der Client kann.

| Änderung | Neue Fassung? |
|---|---|
| Neues optionales Feld in Anfrage oder Antwort | nein |
| Neuer Aufruf, den alte Apps nicht brauchen (z. B. „Code erneut senden“) | nein |
| Neues **Pflichtfeld** in einer Anfrage | ja |
| Neuer Aufruf oder Schritt, ohne den der Ablauf nicht endet | ja |
| Neue oder geänderte `StepData`-Form, die die App darstellen muss | ja |
| Neue Voraussetzung am Konto (`requires`, z. B. bestätigte E-Mail) | nein, siehe unten |

Eine neue Voraussetzung am Konto ändert den Vertrag nicht. Das Tool wird nur nicht mehr angeboten,
solange das Konto sie nicht erfüllt. Das trifft alte und neue Apps gleich und ist eine Frage der
Journey, nicht der Version.

## 3) Grundidee

**Orchestrator und Tools haben getrennte Pfadräume. Jeder trägt seine Version als Segment im
Pfad.**

| Ebene | Pfad | Version gilt für |
|---|---|---|
| Orchestrator | `/orchestrator/api/v1/…` | Umschlag: Kanäle, Katalog, Texte, `ChannelResponse` |
| Tool | `/tools/api/<toolId>/v<N>/…` | ein Tool: seine Pfade, DTOs und Schritt-Formen |

Die Pfade eines Tools, am Beispiel `enroll-sms` in Fassung 2:

| Aufruf | Pfad |
|---|---|
| Tool am Kanal starten | `POST /tools/api/enroll-sms/v2?channel={channelSessionId}` |
| Antwort darauf | `201`, `Location: /tools/api/enroll-sms/v2/{toolSessionId}` |
| Eingabe, Stand lesen | `PATCH`, `GET /tools/api/enroll-sms/v2/{toolSessionId}` |
| Eigener Unterpfad des Tools | `POST /tools/api/enroll-sms/v2/{toolSessionId}/confirmations` |
| Verlassen, Zurück | `DELETE /tools/api/enroll-sms/v2/{toolSessionId}`, `POST …/back` |

- **Tool** bleibt, was es ist: Methode und Rolle, mit der heutigen toolId `enroll-sms`. Daran
  hängen Herkunft (`ClaimSource`), `amr`, Kandidatenwahl und das eingerichtete Verfahren. Nichts
  davon ändert sich, gespeicherte Daten bleiben gültig.
- **Fassung** ist der Vertrag mit dem Client: Pfade, DTOs, Schritt-Formen. Sie steht nur im Pfad.
  Jedes Tool beginnt mit `v1`; es gibt keinen Pfad ohne Fassung.
- **Der Client sagt, was er kann.** In `availableTools` nennt er je Tool die eine Fassung, die er
  beherrscht, in der Form `<toolId>@<version>`: eine alte App `enroll-sms@1`, eine neue
  `enroll-sms@2`. Der Server muss keine App-Versionsnummern kennen.
- **Der Kanal hält die Fassung fest.** Er behält sie für seine Lebensdauer. Der Server bietet ein
  Tool nur an, wenn er die genannte Fassung führt. `next.toolId` nennt weiter nur die toolId; den
  Pfad baut der Client mit seiner Fassung. Ein Aufruf mit einer anderen Fassung im Pfad, als der
  Kanal kennt, wird abgelehnt.
- **Verlassen und Zurück** haben in jedem Tool und jeder Fassung dieselbe Form. Sie liegen im
  Pfadraum des Tools, damit eine Tool-Sitzung nur eine Adresse hat.

## 4) Beispiel

`enroll-sms` soll künftig eine Einwilligung verlangen (`consent`, Pflichtfeld) und danach einen
neuen Schritt „Nummer bestätigen“ mit eigenem Aufruf.

| | alte App | neue App |
|---|---|---|
| nennt in `availableTools` | `enroll-sms@1` | `enroll-sms@2` |
| bekommt angeboten | `enroll-sms` | `enroll-sms` |
| startet mit | `POST /tools/api/enroll-sms/v1?channel=…` | `POST /tools/api/enroll-sms/v2?channel=…` |
| `PATCH /tools/api/enroll-sms/v…/{id}` | ohne `consent` | `consent` ist Pflicht, sonst `missing-fields` |
| neuer Schritt | entfällt | `POST /tools/api/enroll-sms/v2/{id}/confirmations` |
| Ergebnis | Telefonnummer eingerichtet, Herkunft `enroll-sms` | dasselbe |

Beide Apps richten dasselbe Verfahren `sms` ein. Ein Konto, das mit der alten App eingerichtet
wurde, meldet sich mit der neuen an und umgekehrt.

## 5) Was die alte Fassung ohne das Neue tut

Das ist die eigentliche fachliche Entscheidung, und sie fällt je Änderung im Modul. Drei Antworten
sind möglich:

1. **Ersatzwert.** Die alte Fassung setzt einen festen Wert ein (`consent` gilt als nicht erteilt,
   der Ablauf läuft trotzdem).
2. **Geringeres Ergebnis.** Die alte Fassung endet, liefert aber weniger: ein niedrigeres Niveau
   oder eine Angabe weniger. Die Journey kann danach ein anderes Tool anbieten.
3. **Geht nicht.** Ohne das Neue darf das Tool nicht mehr laufen (z. B. eine gesetzliche Pflicht).
   Dann gibt es keine Übergangszeit: Die alte Fassung wird sofort gesperrt, und alte Apps brauchen
   den Hinweis aus Abschnitt 7.

## 6) Umsetzung im Server

**Ein Handler, je Fassung ein Controller.**

- Der Handler ist die Fachlogik und kennt den aktuellen Stand. Er bekommt die Fassung über den
  `ToolContext` und verzweigt an den wenigen Stellen, an denen sich das Verhalten unterscheidet
  (Pflichtfeld prüfen, Schritt überspringen). Jede solche Stelle ist als „entfällt mit v1“ markiert.
- Je Fassung gibt es einen eigenen Controller mit eigenen DTOs unter dem Pfad seiner Fassung. Der
  alte bleibt, wie er ist; der neue hat das Pflichtfeld und den neuen Aufruf. So bleibt die alte
  Vertragsdatei unverändert, und der Vergleich aus ADR-50 bewacht sie weiter.
- Schritt-Formen werden je Fassung deklariert, nicht mehr je Modul. Eine geänderte Form bekommt
  eine neue `kind`; die alte Fassung antwortet weiter mit der alten.

**Änderungen am Modell:**

| Stelle | Heute | Mit Fassungen |
|---|---|---|
| Tool-Pfade | `/orchestrator/api/v1/channels/{id}/tools/<toolId>` und `/orchestrator/api/v1/tools/{id}/<toolId>` | `/tools/api/<toolId>/v<N>/…` (Abschnitt 3) |
| `Tool` (`Tool.kt`) | `toolId` | toolId unverändert, dazu die geführten Fassungen |
| `ToolRole.toolIdFor`, `ClaimSource`, `amrSourceId` | toolId | unverändert |
| `availableTools` | Liste von toolIds | Liste von `<toolId>@<version>`, je toolId höchstens ein Eintrag |
| Kanal (`available_tools`) | toolIds | toolId mit Fassung |
| `ToolCatalog.toolOf(method, role)` | ein Tool | unverändert; die Fassung kommt vom Kanal |
| `tool_availability` | je toolId und Kanaltyp | zusätzlich je Fassung sperrbar; `position` bleibt je toolId |
| `GET /tools/catalog` | ein Eintrag je Tool | ein Eintrag je Tool mit den geführten Fassungen |
| `LeaveToolController` | generisch unter dem Orchestrator-Präfix | generisch unter `/tools/api/{toolId}/v{N}/{toolSessionId}` |
| Umfang des Vertrags | alles unter `/orchestrator/api/v1` außer `/kc` | dasselbe plus `/tools/api/**` |
| Vertragsdateien | `api/published/tools/<toolId>.yaml` | `api/published/tools/<toolId>/v<N>.yaml` |

Der neue Pfadraum `/tools/api` braucht je einen Eintrag im Vite-Proxy, in `compose.yml` und in der
OpenShift-Route. Die Keycloak-Erweiterung erreicht ihn über dieselbe Basis-URL wie den Orchestrator.
DPoP und Peer-Auth prüfen die aufgerufene URL (`htu`) und brauchen keine Änderung im Server; die
Clients signieren den neuen Pfad.

Die Fassung am Kanal lebt nur so lange wie der Kanal und braucht keine Migration. Die Arbeitsdaten
einer Tool-Sitzung gehören dem Modul; ein Durchlauf wechselt die Fassung nie.

**Reihenfolge beim Ausrollen:** erst der Server mit beiden Fassungen, dann die App. Nennt eine neue
App `enroll-sms@2` gegenüber einem alten Server, führt der die Fassung nicht, und das Tool fehlt.
Nach Veröffentlichung der neuen App kann der Server deshalb nicht mehr hinter diese Fassung zurück.

## 7) Die alte Fassung entfernen

1. **Beobachten.** Eine Metrik zählt Aktivierungen je Tool und Fassung und Kanäle, die die alte
   Fassung nennen. Die gibt es noch nicht.
2. **Sperren.** Die alte Fassung wird über `tool_availability` abgeschaltet. Das wirkt sofort und
   lässt sich zurücknehmen; es ist die Probe vor dem Ausbau.
3. **Ausbauen.** Controller, DTOs und die markierten Zweige im Handler der alten Fassung werden
   gelöscht. Die eingefrorene Datei `api/published/tools/enroll-sms/v1.yaml` entfällt; der Vergleich
   meldet das als Hinweis.
4. **Untergrenze.** Das Tool führt danach die Fassungen ab `v2`. Nennt ein Client eine kleinere,
   weiß der Server: Diese App ist zu alt. Eine größere als die höchste geführte heißt dagegen: Der
   Server ist älter als die App.

**Was die alte App dann sieht.** Sie bekommt das Tool nicht mehr angeboten. Ist es ihr einziger Weg
zur Anmeldung, steht der Nutzer ohne Erklärung da. Deshalb meldet der Server im Umschlag, dass der
Client veraltete Fassungen nennt (ein optionales Feld, z. B. `channel.clientOutdated`), und die App
zeigt „Bitte aktualisieren“.

## 8) Was vor der ersten Veröffentlichung der App feststehen muss

In eine veröffentlichte App lässt sich nichts nachrüsten. Diese Punkte gehören deshalb in die erste
Fassung, auch wenn eine zweite Tool-Fassung erst später gebaut wird:

- **Die Pfade.** Jedes Tool liegt unter `/tools/api/<toolId>/v1/…`.
- **`availableTools` nennt die Fassung** (`enroll-sms@1`).
- **Der Hinweis „App veraltet“** im Umschlag, und die App wertet ihn aus.
- **Die Antwort eines abgelösten Orchestrators** (Abschnitt 9): Status und Form stehen fest, und
  die App zeigt darauf „Bitte aktualisieren“.
- **Die App kommt mit einem leeren Angebot zurecht** und zeigt dann eine verständliche Meldung.
- **Die App nennt nur Tools, die sie wirklich darstellen kann.** Das tut sie heute
  (`frontend/src/tools/registry.ts`).

Alles andere (mehrere Fassungen im Modell, Sperre je Fassung, Untergrenze) kann der Server später
allein einführen.

## 9) Verhältnis der beiden Ebenen

Beide Ebenen nutzen denselben Mechanismus, sind aber verschieden streng.

- **Sie sind unabhängig.** Ein Tool-Pfad nennt keine Orchestrator-Version, ein Orchestrator-Pfad
  keine Tool-Fassung. Eine neue Fassung von `enroll-sms` berührt weder die anderen Tools noch den
  Orchestrator.
- **Eine Tool-Fassung hat eine Übergangszeit.** Alte und neue Fassung laufen nebeneinander, bis die
  alte ausgebaut wird (Abschnitt 7).
- **Eine Orchestrator-Version ist ein Pflichtupdate.** Der Server führt immer genau eine. Jeder
  Tool-Endpunkt antwortet mit dem Umschlag (`ChannelResponse`), und zwar in der Form dieser einen
  Version. Der Kanal muss sich keine Orchestrator-Version merken, und kein Tool muss zwei
  Umschlag-Formen ausgeben.
- **Der abgelöste Pfad meldet nur noch „App veraltet“.** Kommt `/orchestrator/api/v2`, antwortet
  alles unter `/orchestrator/api/v1` mit `410` und einer festen, kleinen Form. Alte Apps zeigen
  darauf „Bitte aktualisieren“.
- **Folge für den Umschlag:** Ein Bruch sperrt alle alten Apps auf einmal aus. Der Umschlag ändert
  sich deshalb möglichst nur additiv; eine neue Orchestrator-Version ist der letzte Ausweg.
- **Die Keycloak-Endpunkte** unter `/orchestrator/api/v1/kc` bleiben außerhalb des eingefrorenen
  Vertrags, weil die Erweiterung mit dem Server ausgeliefert wird (ADR-50).

## 10) Erwogene Alternativen

- **Fassung in der toolId** (`enroll-sms-v2`, der frühere Stand dieses Dokuments). Zwei
  Mechanismen: Pfadsegment für den Orchestrator, Namenssuffix für Tools. Die toolId auf der Leitung
  wiche von der gespeicherten Herkunft ab, das Suffix müsste reserviert werden, und die erste
  Fassung wäre ein Sonderfall ohne Suffix.
- **Orchestrator-Version auch im Tool-Pfad** (`/orchestrator/api/v1/tools/enroll-sms/v2/…`). Die
  Ebenen wären im Pfad gekoppelt; mit einer neuen Orchestrator-Version änderte sich jeder Tool-Pfad.
- **Zwei Orchestrator-Versionen nebeneinander.** Der Kanal müsste sich seine Version merken, und
  jedes Tool müsste den Umschlag in beiden Formen ausgeben. Das Pflichtupdate ist einfacher, und
  ein Bruch im Umschlag ist selten (ADR-50).
- **App-Version im Aufruf mitsenden.** Der Server müsste zu jeder App-Version wissen, was sie kann.
  Das koppelt ihn an die Veröffentlichungen der App. Die Fassung je Tool sagt dasselbe genauer.
- **Neue Methode statt neuer Fassung** (`sms2`). Das teilt eingerichtete Verfahren, `amr` und
  Herkunft; ein Konto hätte zwei Verfahren für dieselbe Nummer.
- **Eine Version für alles** (`/api/v2`). Zu grob: Alle Tools und der Umschlag müssten doppelt
  geführt werden, obwohl sich ein Tool ändert.
- **Fähigkeiten statt Fassungen** (`capabilities: ["sms-consent"]`). Feiner, aber jede Kombination
  müsste getestet und eingefroren werden. Fassungen sind eine Reihe, keine Menge.

## 11) Offene Punkte

- **Kanal beim Tool-Start.** Der Vorschlag ist der Query-Parameter `channel`, weil der Body dem
  Tool gehört und der Kanal bei allen Tools gleich angegeben wird. Die Alternative ist ein Feld im
  Body. `ToolContextResolver` und der Logging-Filter lesen den Kanal heute aus dem Pfad.
- **Status bei falscher Fassung.** Welcher Fehler kommt, wenn der Pfad eine andere Fassung nennt
  als der Kanal, und welcher bei einer ausgebauten Fassung.
- **Pakete.** Heute liegt alles in `tools/<modul>/api/v1`. Mit Fassungen je Tool braucht es einen
  Schnitt je Tool und Fassung.
- **Wie viele Fassungen zugleich.** Der Vorschlag ist höchstens zwei; eine dritte erst, wenn die
  erste ausgebaut ist. Sonst wachsen die Zweige im Handler.
