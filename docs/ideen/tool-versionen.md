# Idee: Tools einzeln versionieren

Status: **Konzept, nicht umgesetzt** (Stand 2026-10-04, Issue `DPoP-demo-7luc`). Das Dokument
beschreibt, wie ein Tool in zwei Fassungen nebeneinander läuft, wenn die App veröffentlicht ist und
mehrere App-Versionen zugleich im Einsatz sind. Es baut auf
[ADR-50](../adr/ADR-050-api-versionierung-umschlag-und-tool.md) auf: Der Vertrag ist schon je Tool
eingefroren, eine zweite Fassung desselben Tools lässt das Modell aber noch nicht zu.

---

## 1) Ausgangslage und Annahmen

- Die App ist veröffentlicht. Alte App-Versionen bleiben im Einsatz, der Server kann sie nicht
  aktualisieren.
- Ein Tool muss sich so ändern, dass eine alte App es nicht mehr bedienen kann: Es braucht ein
  neues Pflichtfeld oder einen neuen Aufruf, ohne den der Ablauf nicht endet.
- Alte und neue App sollen beide weiter funktionieren. Später fällt die alte Fassung weg.
- Der Web-Kanal ist nicht betroffen: Die Keycloak-Erweiterung wird mit dem Server ausgeliefert und
  spricht immer die neueste Fassung.

Was es heute schon gibt:

- `availableTools` ist Pflicht. Der Kanal merkt sich, welche toolIds der Client bedienen kann; ein
  anderes Tool wird nie angeboten.
- `tool_availability` sperrt ein Tool je Kanaltyp zur Laufzeit (ADR-32).
- `checkPublishedApiCompatibility` hält jede Tool-Datei gegen ihren eingefrorenen Stand. Ein neues
  oder entfallenes Tool ist ein Hinweis, kein Bruch.

Was fehlt: Die toolId folgt fest aus Methode und Rolle (`ToolRole.toolIdFor`), jede Rolle gibt es
je Modul einmal, und `ToolCatalog.toolOf(method, role)` rechnet mit genau einem Tool.

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

**Ein Tool hat Fassungen. Die Fassung steht in der toolId auf der Leitung, nicht im Fachmodell.**

- **Tool** bleibt, was es ist: Methode und Rolle, z. B. `enroll-sms`. Daran hängen Herkunft
  (`ClaimSource`), `amr`, Kandidatenwahl und das eingerichtete Verfahren. Nichts davon ändert sich,
  gespeicherte Daten bleiben gültig.
- **Fassung** ist der Vertrag mit dem Client: Pfade, DTOs, Schritt-Formen. Die erste Fassung behält
  die heutige toolId, jede weitere bekommt ein Suffix: `enroll-sms`, `enroll-sms-v2`.
- **Der Client sagt, was er kann.** Eine alte App nennt in `availableTools` `enroll-sms`, eine neue
  `enroll-sms-v2`. Mehr Verhandlung gibt es nicht; der Server muss keine App-Versionsnummern kennen.
- **Der Kanal legt die Fassung fest.** Beim Anlegen wählt der Server je Tool die höchste genannte
  und nicht gesperrte Fassung. Der Kanal behält sie für seine Lebensdauer. Ab da gibt es je Kanal
  wieder genau ein Tool je Methode und Rolle, und die Journeys bleiben unverändert.

Die neue toolId erscheint überall, wo der Client sie sieht: in `next.toolId`, in den Pfaden
`channels/{id}/tools/enroll-sms-v2` und `tools/{id}/enroll-sms-v2`, im Katalog.

## 4) Beispiel

`enroll-sms` soll künftig eine Einwilligung verlangen (`consent`, Pflichtfeld) und danach einen
neuen Schritt „Nummer bestätigen“ mit eigenem Aufruf.

| | alte App | neue App |
|---|---|---|
| nennt in `availableTools` | `enroll-sms` | `enroll-sms-v2` |
| bekommt angeboten | `enroll-sms` | `enroll-sms-v2` |
| `PATCH tools/{id}/…` | ohne `consent` | `consent` ist Pflicht, sonst `missing-fields` |
| neuer Schritt | entfällt | `POST tools/{id}/enroll-sms-v2/confirmations` |
| Ergebnis | Telefonnummer eingerichtet, Herkunft `enroll-sms` | dasselbe, Herkunft `enroll-sms` |

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
- Je Fassung gibt es einen eigenen Controller mit eigenen DTOs. Der alte bleibt, wie er ist; der
  neue hat das Pflichtfeld und den neuen Aufruf. So bleibt die alte Vertragsdatei unverändert, und
  der Vergleich aus ADR-50 bewacht sie weiter.
- Schritt-Formen werden je Fassung deklariert, nicht mehr je Modul. Eine geänderte Form bekommt
  eine neue `kind`; die alte Fassung antwortet weiter mit der alten.

**Änderungen am Modell:**

| Stelle | Heute | Mit Fassungen |
|---|---|---|
| `ToolModule.register` (`Tool.kt`) | eine Rolle je Modul, toolId = `toolIdFor(method)` | mehrere Fassungen je Rolle; toolId = Basis-Id plus `-vN` ab der zweiten |
| `Tool` | `toolId` | zusätzlich `version` und die Basis-Id |
| `ClaimSource`, `amrSourceId` | toolId | Basis-Id; die gespeicherten Werte bleiben gleich, keine Migration |
| `ChannelService.catalogToolsOf` | behält, was der Katalog kennt | behält je Tool die höchste genannte, nicht gesperrte Fassung |
| `ToolCatalog.toolOf(method, role)` | erstes Tool | das Tool in der Fassung des Kanals |
| `tool_availability` | je toolId | unverändert; damit lässt sich eine Fassung einzeln sperren |
| `GET /tools/catalog` | ein Eintrag je Tool | ein Eintrag je Fassung, mit `version` |
| Vertragsdateien | `api/contract/tools/<toolId>.yaml` | unverändert, eine Datei je Fassung |

Kurzlebige Daten (`available_tools` am Kanal, das Angebot in der Journey) tragen die toolId der
Fassung. Sie leben nur so lange wie der Kanal und brauchen keine Migration. Die Arbeitsdaten einer
Tool-Sitzung gehören dem Modul; ein Durchlauf wechselt die Fassung nie.

**Reihenfolge beim Ausrollen:** erst der Server mit beiden Fassungen, dann die App. Nennt eine neue
App `enroll-sms-v2` gegenüber einem alten Server, kennt der Katalog die Id nicht, und das Tool
fehlt. Nach Veröffentlichung der neuen App kann der Server deshalb nicht mehr hinter diese Fassung
zurück.

## 7) Die alte Fassung entfernen

1. **Beobachten.** Eine Metrik zählt Aktivierungen je toolId und Kanäle, die nur die alte Fassung
   nennen. Die gibt es noch nicht.
2. **Sperren.** Die alte Fassung wird über `tool_availability` abgeschaltet. Das wirkt sofort und
   lässt sich zurücknehmen; es ist die Probe vor dem Ausbau.
3. **Ausbauen.** Controller, DTOs und die markierten Zweige im Handler der alten Fassung werden
   gelöscht. Die eingefrorene Datei `api/published/tools/enroll-sms.yaml` entfällt; der Vergleich
   meldet das als Hinweis. Das Tool heißt fachlich weiter `enroll-sms`, auf der Leitung gibt es nur
   noch `enroll-sms-v2`.
4. **Grabstein.** Das Modul merkt sich die entfernte toolId (`retired("enroll-sms")`). Nennt ein
   Client sie noch, weiß der Server: Diese App ist zu alt.

**Was die alte App dann sieht.** Sie bekommt das Tool nicht mehr angeboten. Ist es ihr einziger Weg
zur Anmeldung, steht der Nutzer ohne Erklärung da. Deshalb meldet der Server im Umschlag, dass der
Client veraltete Tools nennt (ein optionales Feld, z. B. `channel.clientOutdated`), und die App
zeigt „Bitte aktualisieren“.

## 8) Was vor der ersten Veröffentlichung der App feststehen muss

In eine veröffentlichte App lässt sich nichts nachrüsten. Diese Punkte gehören deshalb in die erste
Fassung, auch wenn Tool-Fassungen erst später gebaut werden:

- **Der Hinweis „App veraltet“** im Umschlag, und die App wertet ihn aus.
- **Die App kommt mit einem leeren Angebot zurecht** und zeigt dann eine verständliche Meldung.
- **Die App nennt nur toolIds, die sie wirklich darstellen kann.** Das tut sie heute
  (`frontend/src/tools/registry.ts`).
- **Das Suffix `-vN` ist reserviert:** Kein Methodenname endet so.

Alles andere (Fassungen im Modell, Auswahl am Kanal, Grabstein) kann der Server später allein
einführen.

## 9) Verhältnis zur API-Version im Pfad

`v1` in `/orchestrator/api/v1` versioniert mit dieser Lösung nur noch den Umschlag.

- **Tools brauchen das Präfix nicht.** Ihre Fassung steht in der toolId, der Client wählt sie über
  `availableTools`. Wegen eines Tools gibt es nie ein `/api/v2`.
- **Der Umschlag braucht es.** `ChannelResponse`, `POST /app/channels`, `tools/catalog` und `texts`
  nutzt jeder Client, und der erste Aufruf kommt, bevor es einen Kanal gibt, an dem sich etwas
  aushandeln ließe. Bricht der Umschlag, bekommen alte Apps unter `/api/v1` weiter die alte Form;
  die neue liegt unter `/api/v2`, mit eigenen Controllern und eigener eingefrorener Datei
  (`api/published/v2/envelope.yaml`).
- **Beide Ebenen sind unabhängig.** Jeder Tool-Endpunkt antwortet mit dem Umschlag. Ein Umschlag v2
  heißt deshalb, dass alle Tool-Endpunkte auch unter `/api/v2` erreichbar sind, etwa
  `/api/v2/tools/{id}/enroll-sms-v2`. Die Tool-Controller hängen dann unter beiden Präfixen, und das
  Präfix bestimmt die Form des Umschlags. Die Tool-Dateien des Vertrags ändern sich dadurch nicht:
  Dort ist der Umschlag nur ein Platzhalter (ADR-50).
- **Die Keycloak-Endpunkte** unter `/api/v1/kc` brauchen keine Version, weil die Erweiterung mit dem
  Server ausgeliefert wird. Das Präfix stört dort nicht und bleibt.

Weglassen ließe sich das Präfix: Pfade ohne Präfix wären dann stillschweigend v1, und `/v2` käme
erst mit dem ersten Bruch. Das spart ein Pfadsegment und macht die erste Version zum Sonderfall;
der Vorschlag ist, es zu behalten.

## 10) Erwogene Alternativen

- **App-Version im Aufruf mitsenden.** Der Server müsste zu jeder App-Version wissen, was sie kann.
  Das koppelt ihn an die Veröffentlichungen der App. Die toolId sagt dasselbe genauer.
- **Neue Methode statt neuer Fassung** (`sms2`). Das teilt eingerichtete Verfahren, `amr` und
  Herkunft; ein Konto hätte zwei Verfahren für dieselbe Nummer.
- **Neue API-Version** (`/api/v2`). Zu grob: Alle Tools und der Umschlag müssten doppelt geführt
  werden, obwohl sich ein Tool ändert.
- **Fähigkeiten statt Fassungen** (`capabilities: ["sms-consent"]`). Feiner, aber jede Kombination
  müsste getestet und eingefroren werden. Fassungen sind eine Reihe, keine Menge.

## 11) Offene Punkte

- **Rückfall bei Sperre.** Ist `-v2` gesperrt und nennt ein Client beide Fassungen, bekommt ein
  neuer Kanal `-v1`. Ob das gewollt ist, hängt vom Grund der Sperre ab; für Fall 3 aus Abschnitt 5
  wäre es falsch.
- **Reihenfolge der Angebote.** `tool_availability.position` gilt je toolId. Eine neue Fassung
  sollte den Platz der alten erben.
- **Frontend.** `enrollmentToolOf(method)` rechnet mit einem Einrichtungs-Tool je Methode und muss
  auf die bekannten toolIds filtern.
- **Wie viele Fassungen zugleich.** Der Vorschlag ist höchstens zwei; eine dritte erst, wenn die
  erste ausgebaut ist. Sonst wachsen die Zweige im Handler.
