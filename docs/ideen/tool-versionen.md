# Idee: Zweite Fassung eines Tools und Abschied von alten Clients

Status: **Konzept, nicht umgesetzt** (Stand 2026-10-04). Wie Orchestrator und Tools versioniert
sind, steht in [ADR-51](../adr/ADR-051-versionen-als-pfadsegment.md) und
[05-api.md](../05-api.md) Abschnitt 1: getrennte Pfadräume, die Fassung als Pfadsegment
(`/tools/api/<toolId>/v<N>`), `availableTools` als `<toolId>@<version>`, eine neue
Orchestrator-Version als Pflichtupdate. Gebaut ist davon die Grundlage; jedes Tool führt bisher nur
`v1`. Dieses Dokument beschreibt, was dazukommt, sobald ein Tool eine zweite Fassung braucht und alte
Clients verabschiedet werden.

---

## 1) Wann es überhaupt eine neue Fassung braucht

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

## 2) Beispiel

`enroll-sms` soll künftig eine Einwilligung verlangen (`consent`, Pflichtfeld) und danach einen
neuen Schritt „Nummer bestätigen“ mit eigenem Aufruf.

| | alte App | neue App |
|---|---|---|
| nennt in `availableTools` | `enroll-sms@1` | `enroll-sms@2` |
| startet mit | `POST /tools/api/enroll-sms/v1?channel=…` | `POST /tools/api/enroll-sms/v2?channel=…` |
| `PATCH /tools/api/enroll-sms/v…/{id}` | ohne `consent` | `consent` ist Pflicht, sonst `missing-fields` |
| neuer Schritt | entfällt | `POST /tools/api/enroll-sms/v2/{id}/confirmations` |
| Ergebnis | Telefonnummer eingerichtet, Herkunft `enroll-sms` | dasselbe |

Beide Apps richten dasselbe Verfahren `sms` ein. Ein Konto, das mit der alten App eingerichtet
wurde, meldet sich mit der neuen an und umgekehrt.

## 3) Was die alte Fassung ohne das Neue tut

Das ist die eigentliche fachliche Entscheidung, und sie fällt je Änderung im Modul. Drei Antworten
sind möglich:

1. **Ersatzwert.** Die alte Fassung setzt einen festen Wert ein (`consent` gilt als nicht erteilt,
   der Ablauf läuft trotzdem).
2. **Geringeres Ergebnis.** Die alte Fassung endet, liefert aber weniger: ein niedrigeres Niveau
   oder eine Angabe weniger. Die Journey kann danach ein anderes Tool anbieten.
3. **Geht nicht.** Ohne das Neue darf das Tool nicht mehr laufen (z. B. eine gesetzliche Pflicht).
   Dann gibt es keine Übergangszeit: Die alte Fassung wird sofort gesperrt, und alte Apps brauchen
   den Hinweis aus Abschnitt 5.

## 4) Eine zweite Fassung bauen

**Ein Handler, je Fassung ein Controller.**

- Das Modul nennt beide Fassungen: `versions = setOf(1, 2)`.
- Der Handler ist die Fachlogik und kennt den aktuellen Stand. Er bekommt die Fassung über
  `ToolContext.version` und verzweigt an den wenigen Stellen, an denen sich das Verhalten
  unterscheidet (Pflichtfeld prüfen, Schritt überspringen). Jede solche Stelle ist als „entfällt
  mit v1“ markiert.
- Der neue Controller liegt unter `/tools/api/<toolId>/v2` und hat eigene DTOs. Der alte bleibt,
  wie er ist; so bleibt `api/published/tools/<toolId>/v1.yaml` unverändert und bewacht ihn weiter.
- Schritt-Formen werden heute je Modul deklariert. Mit einer zweiten Fassung braucht es sie je
  Fassung: Eine geänderte Form bekommt eine neue `kind`, die alte Fassung antwortet weiter mit der
  alten.

**Reihenfolge beim Ausrollen:** erst der Server mit beiden Fassungen, dann die App. Nennt eine neue
App `enroll-sms@2` gegenüber einem alten Server, führt der die Fassung nicht, und das Tool fehlt.
Nach Veröffentlichung der neuen App kann der Server deshalb nicht mehr hinter diese Fassung zurück.

## 5) Die alte Fassung entfernen

1. **Beobachten.** Eine Metrik zählt Aktivierungen je Tool und Fassung und Kanäle, die die alte
   Fassung nennen. Die gibt es noch nicht.
2. **Sperren.** Die alte Fassung wird abgeschaltet. `tool_availability` sperrt heute je toolId und
   Kanaltyp; es braucht dazu eine Sperre je Fassung. Das wirkt sofort und lässt sich zurücknehmen;
   es ist die Probe vor dem Ausbau.
3. **Ausbauen.** Controller, DTOs und die markierten Zweige im Handler der alten Fassung werden
   gelöscht, die Fassung verschwindet aus `versions`. Die eingefrorene Datei
   `api/published/tools/enroll-sms/v1.yaml` entfällt; der Vergleich meldet das als Hinweis.
4. **Untergrenze.** Das Tool führt danach die Fassungen ab `v2`. Nennt ein Client eine kleinere,
   weiß der Server: Diese App ist zu alt. Eine größere als die höchste geführte heißt dagegen: Der
   Server ist älter als die App.

**Was die alte App dann sieht.** Sie bekommt das Tool nicht mehr angeboten. Ist es ihr einziger Weg
zur Anmeldung, steht der Nutzer ohne Erklärung da. Deshalb meldet der Server im Umschlag, dass der
Client veraltete Fassungen nennt (ein optionales Feld, z. B. `channel.clientOutdated`), und die App
zeigt „Bitte aktualisieren“.

## 6) Eine neue Orchestrator-Version

Sie ist ein Pflichtupdate (ADR-51): Der Server führt genau eine. Kommt `/orchestrator/api/v2`,
antwortet alles unter `/orchestrator/api/v1` nur noch mit `410` und einer festen, kleinen Form;
alte Apps zeigen darauf „Bitte aktualisieren“. Status und Form dieser Antwort lassen sich in eine
veröffentlichte App nicht nachrüsten.

## 7) Was vor der ersten Veröffentlichung der App feststehen muss

Gebaut sind die Pfade mit Fassung und `availableTools` mit Fassung. Offen sind:

- **Der Hinweis „App veraltet“** im Umschlag, und die App wertet ihn aus.
- **Die Antwort eines abgelösten Orchestrators** (Abschnitt 6), und die App wertet sie aus.
- **Die App kommt mit einem leeren Angebot zurecht** und zeigt dann eine verständliche Meldung.

## 8) Offene Punkte

- **Pakete.** Heute liegt alles in `tools/<modul>/api/v1`. Mit einer zweiten Fassung braucht es
  einen Schnitt je Tool und Fassung.
- **Wie viele Fassungen zugleich.** Der Vorschlag ist höchstens zwei; eine dritte erst, wenn die
  erste ausgebaut ist. Sonst wachsen die Zweige im Handler.
