# Idee: Abschied von alten Fassungen und alten Clients

**Worum es geht.** Ein [Tool](../glossar/glossar.md) ist ein abgeschlossener Arbeitsschritt, den der
Nutzer durchläuft, etwa „SMS einrichten“. Ändert sich ein Tool so, dass alte Apps es nicht mehr
richtig bedienen können, bekommt es eine zweite Fassung, und die alte läuft eine Weile weiter.
Irgendwann soll der Server die alte Fassung nicht mehr führen. Alte Apps müssen ihren Nutzern dann
sagen, dass sie aktualisieren müssen.

**Warum das wichtig ist.** Eine veröffentlichte App lässt sich nicht nachträglich ändern. Viele
Nutzer aktualisieren spät. Funktioniert eine App ohne Erklärung nicht mehr, ist das für den Nutzer
ein echtes Problem. Was eine alte App dafür auswerten muss, muss schon in ihrer ersten
veröffentlichten Version stecken.

**Stand: teilweise umgesetzt** (Stand 2026-10-08). Gebaut ist, wie ein Tool eine zweite Fassung
bekommt und beide nebeneinander laufen. Das erste Beispiel ist `enroll-sms@2` mit einer
Einwilligung. Maßgeblich dafür sind [ADR-51](../adr/ADR-051-versionen-als-pfadsegment.md), die
Seite des [Verfahrens `sms`](../verfahren/sms.md), [05-api.md](../05-api.md) Abschnitt 2
(„Fassungen eines Tools“) und
[15-beispiel-neues-verfahren-backend.md](../15-beispiel-neues-verfahren-backend.md) Abschnitt 10.
Gebaut ist auch die Sperre je Fassung (ADR-32, ADR-51). Offen ist alles, was danach kommt: die alte
Fassung beobachten, ausbauen und alten Clients sagen, dass sie veraltet sind.

---

## 1) Die alte Fassung entfernen

Das Entfernen läuft in vier Schritten, am Beispiel `enroll-sms@1`:

1. **Beobachten.** Eine Metrik zählt, wie oft jedes Tool in jeder Fassung gestartet wird und
   welche Kanäle noch die alte Fassung nennen. Diese Metrik gibt es noch nicht.
2. **Sperren.** Der Betreiber sperrt die alte Fassung je Kanaltyp
   (`PUT /orchestrator/admin/tools/enroll-sms@1/availability/APP`). Das ist gebaut. Die Sperre
   wirkt sofort und lässt sich zurücknehmen. Sie ist damit die Probe vor dem endgültigen Ausbau.
3. **Ausbauen.** Controller, DTOs und die mit „entfällt mit v1“ markierten Zweige im Handler
   werden gelöscht, und die Fassung verschwindet aus `versions`. Die eingefrorene Datei
   `api/published/tools/enroll-sms/v1.yaml` entfällt. Der Vergleich der Schnittstellen meldet das
   als Hinweis. Ausgebaut wurde bisher noch keine Fassung.
4. **Untergrenze.** Das Tool führt danach nur noch die Fassungen ab `v2`. Nennt ein Client eine
   kleinere Fassung, weiß der Server: Diese App ist zu alt. Nennt er eine größere als die höchste
   bekannte, heißt das dagegen: Der Server ist älter als die App.

**Was die alte App dann sieht.** Sie bekommt das Tool nicht mehr angeboten. Ist es ihr einziger
Weg zur Anmeldung, kann sich der Nutzer nicht mehr anmelden und erfährt nicht, warum. Deshalb
soll der Server in der Kanalantwort (`ChannelResponse`) melden, dass der Client veraltete
Fassungen nennt. Das wäre ein optionales Feld, zum Beispiel `channel.clientOutdated`. Die App
zeigt daraufhin „Bitte aktualisieren“.

Dasselbe gilt, wenn eine alte Fassung sofort nicht mehr laufen darf, etwa wegen einer gesetzlichen
Pflicht (ADR-51, „Was die alte Fassung ohne das Neue tut“). Dann gibt es keine Übergangszeit, und
der Hinweis ist der einzige Weg, den Nutzer zu erreichen.

## 2) Eine neue Orchestrator-Version

Eine neue Version des Orchestrators ist ein Pflichtupdate (ADR-51). Der Server führt immer genau
eine Version. Kommt `/orchestrator/api/v2`, antwortet alles unter `/orchestrator/api/v1` nur noch
mit dem Status `410` und einer festen, kleinen Form. Alte Apps zeigen darauf „Bitte
aktualisieren“. Status und Form dieser Antwort müssen schon in der ersten veröffentlichten App
feststehen, denn nachrüsten lassen sie sich dort nicht.

## 3) Was vor der ersten Veröffentlichung der App feststehen muss

- **Der Hinweis „App veraltet“** in der Kanalantwort (`ChannelResponse`), und die App muss ihn
  auswerten.
- **Die Antwort eines abgelösten Orchestrators** (Abschnitt 2), und die App muss sie auswerten.
- **Die App kommt mit einem leeren Angebot zurecht.** Bietet der Server kein Tool an, zeigt sie
  eine verständliche Meldung.

## 4) Offene Punkte

- **Wie viele Fassungen zugleich.** Der Vorschlag ist: höchstens zwei. Eine dritte kommt erst,
  wenn die erste ausgebaut ist. Sonst wachsen die Verzweigungen im Handler immer weiter.
