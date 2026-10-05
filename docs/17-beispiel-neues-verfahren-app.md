# Beispiel für App-Entwickler: Bank-Ident und Einmalcode-App in der App

Dies ist das Schwesterkapitel zu
[15-beispiel-neues-verfahren-backend.md](15-beispiel-neues-verfahren-backend.md). Dort bauen
Backend-Entwickler zwei neue Verfahren in den Server und den Web-Kanal ein. Hier bringt ein
App-Entwickler dieselben Verfahren in die App.

„App“ heißt in diesem Kapitel jeder Client des App-Kanals, egal ob native App für iOS und Android
oder eine Web-App. Der App-Kanal ist die Verbindung der App zum Orchestrator, dem Server dieses
Projekts. Die App spricht mit dem Orchestrator nur über den Vertrag unter `api/`, also die
verbindliche Beschreibung der Schnittstelle ([05-api.md](05-api.md)). Jede Anfrage signiert sie per
DPoP mit einem Schlüssel, der auf dem Gerät liegt. So ist die Anfrage an dieses Gerät gebunden
([09-dpop.md](09-dpop.md)).

Die Demo-App unter `frontend/` zeigt beispielhaft, wie ein Client den Vertrag benutzt. Sie ist eine
Referenz, keine Vorlage, der man folgen muss.

Die beiden Verfahren:

- **Bank-Ident** (`ident-bank`): Die Person identifiziert sich über ihre Hausbank. Die App schickt
  sie zur Bank und nimmt sie danach wieder auf. Das Ergebnis holt der Orchestrator selbst bei der
  Bank ab.
- **Einmalcode-App** (`enroll-totp`, `auth-totp`, optional `auth-totp-lookup`): Die Person richtet
  eine Authenticator-App ein und meldet sich danach mit dem sechsstelligen Code aus dieser App an.

Jeder dieser Namen bezeichnet ein **Tool**, also einen abgeschlossenen Arbeitsschritt, den der
Nutzer durchläuft, etwa „Einmalcode-App einrichten“ (`enroll-totp`) oder „mit Einmalcode anmelden“
(`auth-totp`).

---

## 1) Was die App vom Backend bekommt

Das Backend liefert mit seinem Teil alles, was die App braucht. Sobald das da ist, kann die App
beginnen. Im Einzelnen sind das:

- **Den Vertrag je Tool und Fassung:** `api/contract/tools/<toolId>/v<N>.yaml`, etwa
  `api/contract/tools/enroll-totp/v1.yaml`. Darin stehen:
  - die Pfade,
  - die Felder jedes Aufrufs,
  - die Formen von `stepData`, also der Daten, die der Bildschirm für einen Schritt bekommt. Die
    Formen unterscheidet das Feld `kind`.
  - Beispiele für jede Antwort.

  Was alle Tools gemeinsam haben (`ChannelResponse`, `next`, Fehler), steht in
  `api/contract/envelope.yaml`.
- **Typen**, die man aus `api/openapi.yaml` erzeugt, statt sie von Hand nachzubauen.
- **Den Katalog** `GET /orchestrator/api/v1/tools/catalog`. Er nennt je Tool die Methode, die
  Rolle, die geführten Fassungen sowie Name und Hinweis als Textverweis. Name und Hinweis legt das
  Backend fest. Die App löst sie über die Texte auf (`GET /orchestrator/api/v1/texts/{lang}`,
  [05-api.md](05-api.md) Abschnitt 1).
- **Einen laufenden Server zum Ausprobieren** ([13-ausfuehren.md](13-ausfuehren.md)). Im Demomodus
  stehen Codes und TANs im Block `demo` der Antworten. Darauf darf sich nur ein Test verlassen, nie
  die App, denn außerhalb des Demomodus fehlt der Block.

## 2) Was die App entscheidet und was nicht

Die App steuert keinen Ablauf. In jeder Antwort sagt der Orchestrator im Feld `next`, welcher
Schritt als Nächstes kommt, und die App folgt dieser Angabe ([05-api.md](05-api.md) Abschnitt 1,
[10-frontend.md](10-frontend.md) „Wie `next` die App steuert“):

- `next.type = "tool"` nennt `toolId`, `step` und, sobald es sie gibt, `toolSessionId` (die
  Kennung des einzelnen Durchlaufs eines Tools). Die App zeigt dann den Schritt dieses Tools. Eine
  toolId baut sie nie selbst zusammen. Die toolId kommt immer aus `next` oder aus den Optionen einer
  Auswahl.
- Ein neues Tool braucht in der App deshalb keine Navigation, keinen neuen Bildschirm für den
  Ablauf und keine Bedingung, wann es kommt. Der Orchestrator bietet es an, wenn es passt.

Was die App für ein neues Tool tut:

1. **Deklarieren.** Beim Anlegen des Kanals (`POST /orchestrator/api/v1/app/channels`) nennt die App
   in `availableTools` jedes Tool, das sie darstellen kann. Sie nennt es in der einen Fassung, die
   sie beherrscht: `"ident-bank@1"`, `"enroll-totp@1"`, `"auth-totp@1"`. Was sie nicht nennt,
   bekommt sie nie angeboten. So kann eine App ausgeliefert werden, bevor sie jedes Verfahren kennt.
2. **Aufrufen.** Ein Tool liegt unter `/tools/api/<toolId>/v<N>`
   ([ADR-51](adr/ADR-051-versionen-als-pfadsegment.md)):
   - Starten: `POST /tools/api/<toolId>/v1?channel=<channelSessionId>`. Das geschieht, wenn `next`
     ein Tool ohne `toolSessionId` nennt. Die Antwort ist `201`, die neue Tool-Sitzung steht in
     `next.toolSessionId`.
   - Eingaben schicken: `PATCH /tools/api/<toolId>/v1/<toolSessionId>` mit genau den Feldern, die
     `stepData.missingFields` nennt.
   - Stand lesen: `GET` auf dieselbe Adresse, etwa nach einem Neustart der App.
   - Zurück zur Auswahl: `POST …/<toolSessionId>/back`. Ablehnen: `DELETE …/<toolSessionId>`.
   - Jede Anfrage enthält einen neuen DPoP-Nachweis für die HTTP-Methode und die Adresse ohne
     Query.
3. **Darstellen.** Für jede Kombination aus `toolId` und `step` gibt es einen Bildschirm, gefüllt
   aus `stepData`. Erst wenn die `toolSessionId` feststeht, nimmt der Bildschirm Eingaben an.
   Vorher hätte ein Absenden kein Ziel. Eine Form mit `kind: "failed-attempt"` zeigt den Grund des
   letzten Versuchs als Text. Einen Schritt, den die App nicht kennt, behandelt sie als Fehler und
   nicht als leeren Bildschirm.
4. **Ergebnis übernehmen.** Jede Antwort ist eine `ChannelResponse`. Die App zeigt danach, was das
   neue `next` sagt, auch wenn das ein anderes Tool oder das Ende ist.

## 3) Bank-Ident: zur Bank und zurück

Die Weiterleitung läuft wie bei `ident-nect`
([ADR-47](adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md)):

1. **Starten mit Rücksprungadresse.** Die App gibt beim Starten eine `returnUri` mit. Das ist die
   Adresse, unter der das Betriebssystem nach dem Besuch bei der Bank wieder die App öffnet
   (Universal Link bzw. App Link). Der Server nimmt nur Adressen an, die mit einem freigegebenen
   Präfix beginnen. Dieses Präfix trägt das Backend für die App in die Konfiguration ein. Ohne
   `returnUri` kehrt die Bank zur Web-App der Demo zurück.
2. **Zur Bank.** `stepData` nennt die Sprungadresse. Die App öffnet sie im Browser oder in der App
   der Bank. Vorher speichert sie, was sie für die Rückkehr braucht: die `channelSessionId` und die
   Tool-Sitzung. Denn das Betriebssystem kann die App in der Zwischenzeit beenden.
3. **Zurück.** Die Bank öffnet die Rücksprungadresse mit `?bankCaseId=…`. Die App liest die
   Kennung genau einmal. Sie setzt den gespeicherten Kanal fort
   (`GET /orchestrator/api/v1/channels/<id>`). Sobald der Schritt des Tools wieder angezeigt wird,
   schickt sie die Kennung mit `PATCH` an das Tool.
4. **Was die App nicht tut:** das Ergebnis der Bank lesen oder weitergeben. Die Kennung ist nur
   ein Verweis. Den Inhalt holt der Orchestrator selbst bei der Bank ab. Angaben des Clients
   übernimmt er dabei nicht.

Bricht die Person bei der Bank ab, kommt sie ohne Kennung oder mit einer gescheiterten Kennung
zurück. Den nächsten Schritt nennt dann wie immer `next`, etwa einen neuen Versuch oder ein anderes
Verfahren.

## 4) Einmalcode-App: Einrichten und Anmelden

**Einrichten (`enroll-totp`).** `stepData` nennt die `otpauth://`-Adresse und das Geheimnis. Aus
diesem Geheimnis berechnet die Authenticator-App später ihre Codes.

- **Auf demselben Gerät** ist der häufige Fall: Die Authenticator-App liegt auf dem Telefon, auf
  dem auch die App läuft. Dort kann man keinen QR-Code scannen. Die App öffnet die
  `otpauth://`-Adresse deshalb als Link. Das Betriebssystem gibt sie an die installierte
  Authenticator-App weiter. Ist keine installiert, zeigt die App das Geheimnis zum Abtippen.
- **Auf einem anderen Gerät** hilft ein QR-Code mit der Adresse. Er ist eine Ergänzung, nicht der
  Hauptweg.
- Danach nimmt die App den ersten Code aus der Authenticator-App entgegen und schickt ihn mit
  `PATCH`. Stimmt er nicht, kommt derselbe Schritt mit `failed-attempt` zurück.
- Das Geheimnis zeigt die App nur in diesem Schritt, und sie speichert es nicht.

**Anmelden (`auth-totp`).** Die App braucht ein Eingabefeld für den Code und schickt einen `PATCH`.
Die Fehlversuche zählt der Server. Sind es zu viele, antwortet er mit `423`, und die App zeigt den
Text der Fehlermeldung. Ein Knopf zum Wiederholen oder ein eigener Zähler in der App ist nicht
nötig.

**Ohne bekanntes Konto (`auth-totp-lookup`).** Die Person gibt E-Mail-Adresse und Code in einem
Schritt ein. Die App deklariert dieses Tool nur, wenn sie diesen Einstieg anbietet. Der
Orchestrator bietet das Tool dann von selbst überall dort an, wo ein Konto erst gefunden werden
muss.

## 5) Texte

- **Name und Hinweis eines Tools** kommen aus dem Katalog als Textverweis. Die App schreibt sie
  nicht selbst. Sonst hießen dieselben Verfahren in der App und im Web verschieden.
- **Texte des Servers** (Fehler, Grund eines Fehlversuchs, Hinweise der Journey) kommen als
  Textverweis mit Platzhaltern. Die App löst sie über das Text-Bundle auf. Eine Journey ist ein
  laufender Ablauf mit mehreren Schritten, etwa eine Registrierung.
- **Die eigenen Texte der Bildschirme** (Beschriftungen, Erklärungen) gehören der App, und sie
  werden mit ihr übersetzt.

## 6) Fassungen

Eine App beherrscht je Tool genau eine Fassung. Welche das ist, steht fest im jeweiligen Build der
App ([ADR-51](adr/ADR-051-versionen-als-pfadsegment.md), Regeln in [05-api.md](05-api.md)
Abschnitte 1 und 2):

- **Neue Fassung im Backend.** Bringt das Backend `enroll-totp@2`, muss die App nichts tun. Sie
  verwendet weiter Fassung 1, bis sie das Neue anzeigen will. Dann setzt sie die neue Vertragsdatei
  (`api/contract/tools/enroll-totp/v2.yaml`) um, deklariert `enroll-totp@2` und ruft `/v2` auf. Ein
  Release mit der neuen Fassung wird erst ausgeliefert, wenn der Server sie führt.
- **Gesperrte oder ausgebaute Fassung.** Sperrt der Betreiber die Fassung, die die App verwendet,
  bekommt die App das Tool nicht mehr angeboten. Sie muss dann mit einem kleineren oder leeren
  Angebot zurechtkommen. Ein ausdrücklicher Hinweis „App veraltet“ ist geplant (`DPoP-demo-kkod`).
- **Neue Orchestrator-Version.** Sie erzwingt ein Update der App. Wie der abgelöste Pfad dann
  antwortet, steht noch nicht fest (`DPoP-demo-gd85`). Die App sollte darauf mit der Bitte um ein
  Update reagieren.

## 7) Testen

- **Gegen den Vertrag.** Die Beispiele in den Vertragsdateien sind echte Antworten des Servers. Sie
  eignen sich als Testdaten für jeden Bildschirm, ohne dass ein Server laufen muss.
- **Gegen den Server.** Ein Durchlauf über alle Schritte gegen einen laufenden Orchestrator im
  Demomodus. Codes und TANs stehen dann im Block `demo`. Ein Test der Rückkehr von der Bank muss die
  Rücksprungadresse öffnen, so wie der Test der Rückkehr von Nect in der Referenz
  (`frontend/e2e/nect-return.spec.ts`).
- **Die Deklaration.** Ein Test prüft, dass `availableTools` nur Tools nennt, die die App darstellen
  kann, jedes mit Fassung. Und dass jede deklarierte Fassung zu einem Bildschirm führt.

## 8) Checkliste

- Die Vertragsdateien der Tools sind gelesen, die Typen erzeugt.
- `availableTools` ist um `ident-bank@1`, `enroll-totp@1` und `auth-totp@1` ergänzt (und
  gegebenenfalls um `auth-totp-lookup@1`).
- Es gibt je Tool und Schritt einen Bildschirm. Eingaben sind erst mit `toolSessionId` möglich.
  Unbekannte Schritte werden als Fehler behandelt.
- Die Rücksprungadresse für die Bank ist im Betriebssystem registriert, und ihr Präfix ist dem
  Backend genannt. Kanal und Tool-Sitzung bleiben über einen Neustart der App erhalten.
- `otpauth://` wird als Link geöffnet, das Geheimnis zum Abtippen ist der Ausweg. Nichts davon wird
  gespeichert.
- Texte kommen über Katalog und Text-Bundle, die eigenen Texte sind übersetzt.
- Es gibt Tests gegen die Vertragsbeispiele und gegen einen laufenden Server.
