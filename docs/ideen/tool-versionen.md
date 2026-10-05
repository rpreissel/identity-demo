# Idee: Zweite Fassung eines Tools und Abschied von alten Clients

**Worum es geht.** Ein [Tool](../glossar/glossar.md) ist ein abgeschlossener Arbeitsschritt, den der
Nutzer durchläuft, etwa „SMS einrichten“. Die App spricht mit jedem Tool über eine feste
Schnittstelle. Irgendwann muss sich ein Tool so ändern, dass alte Apps es nicht mehr richtig
bedienen können. Dann braucht das Tool eine zweite Fassung, und die alte muss eine Weile
weiterlaufen.

**Warum das wichtig ist.** Eine veröffentlichte App lässt sich nicht nachträglich ändern. Viele
Nutzer aktualisieren spät. Der Server muss deshalb alte und neue Apps zugleich bedienen.
Irgendwann soll er alte Apps nicht mehr unterstützen und ihren Nutzern dann sagen, dass sie
aktualisieren müssen. Funktioniert eine App ohne Erklärung nicht mehr, ist das für den Nutzer ein
echtes Problem.

**Stand: Konzept, nicht umgesetzt** (Stand 2026-10-04). Wie Orchestrator und Tools versioniert
sind, ist schon entschieden. Das steht in [ADR-51](../adr/ADR-051-versionen-als-pfadsegment.md)
und in [05-api.md](../05-api.md) Abschnitt 1:

- Orchestrator und Tools haben getrennte Pfadräume.
- Die Fassung steht als Pfadsegment in der Adresse (`/tools/api/<toolId>/v<N>`).
- Die App nennt die Tools, die sie kann, in `availableTools` in der Form `<toolId>@<version>`.
- Eine neue Version des Orchestrators ist ein Pflichtupdate.

Gebaut ist davon die Grundlage. Jedes Tool führt bisher nur `v1`. Dieses Dokument beschreibt, was
dazukommt, sobald ein Tool eine zweite Fassung braucht und alte Clients nicht mehr unterstützt werden.

---

## 1) Wann es überhaupt eine neue Fassung braucht

Die meisten Änderungen brauchen keine neue Fassung. Eine Fassung ist nur nötig, wenn der Server
wissen muss, was der Client kann.

| Änderung | Neue Fassung? |
|---|---|
| Neues optionales Feld in Anfrage oder Antwort | nein |
| Neuer Aufruf, den alte Apps nicht brauchen (z. B. „Code erneut senden“) | nein |
| Neues **Pflichtfeld** in einer Anfrage | ja |
| Neuer Aufruf oder Schritt, ohne den der Ablauf nicht endet | ja |
| Neue oder geänderte `StepData`-Form, die die App darstellen muss | ja |
| Neue Voraussetzung am Konto (`requires`, z. B. bestätigte E-Mail) | nein, siehe unten |

`StepData` sind die Daten, mit denen der Server der App sagt, was sie im aktuellen Schritt anzeigen
soll.

Eine neue Voraussetzung am Konto ändert den Vertrag zwischen App und Server nicht. Das Tool wird
nur so lange nicht angeboten, wie das Konto die Voraussetzung nicht erfüllt. Das trifft alte und
neue Apps gleich. Es ist deshalb eine Frage der [Journey](../glossar/glossar.md), also des
geführten Ablaufs, und keine Frage der Version.

## 2) Beispiel

Das Tool `enroll-sms` richtet eine Telefonnummer für die Anmeldung per SMS ein. Es soll künftig
eine Einwilligung verlangen (`consent`, ein Pflichtfeld). Danach soll ein neuer Schritt „Nummer
bestätigen“ mit eigenem Aufruf folgen. Die Tabelle zeigt, wie sich alte und neue App dann
unterscheiden.

| | alte App | neue App |
|---|---|---|
| nennt in `availableTools` | `enroll-sms@1` | `enroll-sms@2` |
| startet mit | `POST /tools/api/enroll-sms/v1?channel=…` | `POST /tools/api/enroll-sms/v2?channel=…` |
| `PATCH /tools/api/enroll-sms/v…/{id}` | ohne `consent` | `consent` ist Pflicht, sonst `missing-fields` |
| neuer Schritt | entfällt | `POST /tools/api/enroll-sms/v2/{id}/confirmations` |
| Ergebnis | Telefonnummer eingerichtet, Herkunft `enroll-sms` | dasselbe |

Beide Apps richten dasselbe Verfahren `sms` ein. Ein Konto, das mit der alten App eingerichtet
wurde, kann sich mit der neuen anmelden und umgekehrt.

## 3) Was die alte Fassung ohne das Neue tut

Das ist die eigentliche fachliche Entscheidung. Sie fällt bei jeder Änderung neu, und zwar im
Modul des Tools. Es gibt drei mögliche Antworten:

1. **Ersatzwert.** Die alte Fassung setzt einen festen Wert ein. Im Beispiel gilt `consent` als
   nicht erteilt, und der Ablauf läuft trotzdem.
2. **Geringeres Ergebnis.** Die alte Fassung läuft bis zum Ende, liefert aber weniger: ein
   niedrigeres Sicherheitsniveau oder eine Angabe weniger. Die Journey kann danach ein anderes Tool
   anbieten.
3. **Geht nicht.** Ohne das Neue darf das Tool nicht mehr laufen, zum Beispiel wegen einer
   gesetzlichen Pflicht. Dann gibt es keine Übergangszeit. Die alte Fassung wird sofort gesperrt,
   und alte Apps brauchen den Hinweis aus Abschnitt 5.

## 4) Eine zweite Fassung bauen

Der Grundsatz lautet: **ein Handler, je Fassung ein Controller.** Der Handler enthält die
Fachlogik des Tools. Der Controller nimmt die HTTP-Aufrufe für genau eine Fassung entgegen.

- Das Modul nennt beide Fassungen: `versions = setOf(1, 2)`.
- Der Handler kennt den aktuellen Stand der Fachlogik. Er erfährt über `ToolContext.version`,
  welche Fassung gerade läuft. An den wenigen Stellen, an denen sich das Verhalten unterscheidet,
  verzweigt er, etwa beim Prüfen eines Pflichtfelds oder beim Überspringen eines Schritts. Jede
  solche Stelle ist als „entfällt mit v1“ markiert.
- Der neue Controller liegt unter `/tools/api/<toolId>/v2` und hat eigene DTOs. Der alte
  Controller bleibt, wie er ist. So bleibt auch `api/published/tools/<toolId>/v1.yaml` unverändert.
  Mit dieser eingefrorenen Beschreibung der Schnittstelle prüft der Build weiterhin, dass sich der
  alte Controller nicht ändert.
- Die Formen der Schritte werden heute je Modul deklariert. Mit einer zweiten Fassung braucht es
  sie je Fassung. Eine geänderte Form bekommt eine neue `kind`. Die alte Fassung antwortet weiter
  mit der alten Form.

**Reihenfolge beim Ausrollen.** Zuerst kommt der Server mit beiden Fassungen, danach die App.
Nennt eine neue App `enroll-sms@2` gegenüber einem alten Server, kennt dieser die Fassung nicht,
und das Tool fehlt. Sobald die neue App veröffentlicht ist, kann der Server deshalb nicht mehr auf
einen Stand ohne diese Fassung zurückgehen.

## 5) Die alte Fassung entfernen

Das Entfernen läuft in vier Schritten:

1. **Beobachten.** Eine Metrik zählt, wie oft jedes Tool in jeder Fassung gestartet wird und
   welche Kanäle noch die alte Fassung nennen. Diese Metrik gibt es noch nicht.
2. **Sperren.** Die alte Fassung wird abgeschaltet. Die Tabelle `tool_availability` sperrt heute
   je toolId und Kanaltyp. Es braucht dazu eine Sperre je Fassung. Die Sperre wirkt sofort und
   lässt sich zurücknehmen. Sie ist damit die Probe vor dem endgültigen Ausbau.
3. **Ausbauen.** Controller, DTOs und die markierten Zweige im Handler der alten Fassung werden
   gelöscht, und die Fassung verschwindet aus `versions`. Die eingefrorene Datei
   `api/published/tools/enroll-sms/v1.yaml` entfällt. Der Vergleich der Schnittstellen meldet das
   als Hinweis.
4. **Untergrenze.** Das Tool führt danach nur noch die Fassungen ab `v2`. Nennt ein Client eine
   kleinere Fassung, weiß der Server: Diese App ist zu alt. Nennt er eine größere als die höchste
   bekannte, heißt das dagegen: Der Server ist älter als die App.

**Was die alte App dann sieht.** Sie bekommt das Tool nicht mehr angeboten. Ist es ihr einziger
Weg zur Anmeldung, kann sich der Nutzer nicht mehr anmelden und erfährt nicht, warum. Deshalb
soll der Server in der Kanalantwort (`ChannelResponse`) melden, dass der Client veraltete
Fassungen nennt. Das wäre ein optionales Feld, zum Beispiel `channel.clientOutdated`. Die App
zeigt daraufhin „Bitte aktualisieren“.

## 6) Eine neue Orchestrator-Version

Eine neue Version des Orchestrators ist ein Pflichtupdate (ADR-51). Der Server führt immer genau
eine Version. Kommt `/orchestrator/api/v2`, antwortet alles unter `/orchestrator/api/v1` nur noch
mit dem Status `410` und einer festen, kleinen Form. Alte Apps zeigen darauf „Bitte
aktualisieren“. Status und Form dieser Antwort müssen schon in der ersten veröffentlichten App
feststehen, denn nachrüsten lassen sie sich dort nicht.

## 7) Was vor der ersten Veröffentlichung der App feststehen muss

Gebaut sind die Pfade mit Fassung und `availableTools` mit Fassung. Offen sind noch drei Punkte:

- **Der Hinweis „App veraltet“** in der Kanalantwort (`ChannelResponse`), und die App muss ihn
  auswerten.
- **Die Antwort eines abgelösten Orchestrators** (Abschnitt 6), und die App muss sie auswerten.
- **Die App kommt mit einem leeren Angebot zurecht.** Bietet der Server kein Tool an, zeigt sie
  eine verständliche Meldung.

## 8) Offene Punkte

- **Pakete.** Heute liegt alles in `tools/<modul>/api/v1`. Mit einer zweiten Fassung braucht es
  eine Aufteilung der Pakete je Tool und Fassung.
- **Wie viele Fassungen zugleich.** Der Vorschlag ist: höchstens zwei. Eine dritte kommt erst,
  wenn die erste ausgebaut ist. Sonst wachsen die Verzweigungen im Handler immer weiter.
