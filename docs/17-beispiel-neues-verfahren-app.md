# Beispiel für App-Entwickler: Bank-Ident und Einmalcode-App in der App

Das Schwesterkapitel zu [15-beispiel-neues-verfahren-backend.md](15-beispiel-neues-verfahren-backend.md):
Dort bauen Backend-Entwickler zwei neue Verfahren in den Server und den Web-Kanal ein, hier bringt
ein App-Entwickler sie in die App. „App“ heißt jeder Client des App-Kanals, ob native App für iOS
und Android oder eine Web-App. Er spricht mit dem Orchestrator nur über den Vertrag unter `api/`
([05-api.md](05-api.md)) und bindet jede Anfrage per DPoP an den Schlüssel des Geräts
([09-dpop.md](09-dpop.md)). Die Demo-App unter `frontend/` ist eine Referenz dafür, wie ein Client
den Vertrag benutzt, keine Vorlage, der man folgen muss.

Die beiden Verfahren:

- **Bank-Ident** (`ident-bank`): Die Person identifiziert sich über ihre Hausbank. Die App schickt sie
  zur Bank und nimmt sie danach wieder auf; das Ergebnis holt der Orchestrator selbst bei der Bank ab.
- **Einmalcode-App** (`enroll-totp`, `auth-totp`, optional `auth-totp-lookup`): Die Person richtet
  eine Authenticator-App ein und meldet sich danach mit dem sechsstelligen Code an.

---

## 1) Was die App vom Backend bekommt

Mit seinem Teil liefert das Backend alles, was die App braucht. Die App beginnt, wenn es da ist:

- **Den Vertrag je Tool und Fassung:** `api/contract/tools/<toolId>/v<N>.yaml`, etwa
  `api/contract/tools/enroll-totp/v1.yaml`. Darin stehen die Pfade, die Felder jedes Aufrufs, die
  Formen von `stepData` (unterschieden an `kind`) und Beispiele für jede Antwort. Was alle Tools
  teilen (`ChannelResponse`, `next`, Fehler), steht in `api/contract/envelope.yaml`.
- **Typen**, die man aus `api/openapi.yaml` erzeugt, statt sie von Hand nachzubauen.
- **Den Katalog** `GET /orchestrator/api/v1/tools/catalog`: je Tool Methode, Rolle, geführte
  Fassungen und Name und Hinweis als Textverweis. Name und Hinweis legt das Backend fest; die App
  löst sie über die Texte auf (`GET /orchestrator/api/v1/texts/{lang}`, [05-api.md](05-api.md)
  Abschnitt 1).
- **Einen laufenden Server zum Ausprobieren** ([13-ausfuehren.md](13-ausfuehren.md)). Im Demomodus
  stehen Codes und TANs im Block `demo` der Antworten. Darauf verlässt sich nur ein Test, nie die
  App: Außerhalb des Demomodus fehlt der Block.

## 2) Was die App entscheidet und was nicht

Die App steuert keinen Ablauf. Sie folgt `next` ([05-api.md](05-api.md) Abschnitt 1,
[10-frontend.md](10-frontend.md) „Wie `next` die App steuert“):

- `next.type = "tool"` nennt `toolId`, `step` und, sobald es eine gibt, `toolSessionId`. Die App zeigt
  den Schritt dieses Tools. Eine toolId baut sie nie selbst zusammen; sie kommt aus `next` oder aus
  den Optionen einer Auswahl.
- Ein neues Tool braucht in der App deshalb keine Navigation, keinen neuen Bildschirm des Ablaufs und
  keine Bedingung, wann es kommt. Der Orchestrator bietet es an, wenn es passt.

Was die App für ein neues Tool tut:

1. **Deklarieren.** Beim Anlegen des Kanals (`POST /orchestrator/api/v1/app/channels`) nennt sie in
   `availableTools` jedes Tool, das sie darstellen kann, in der einen Fassung, die sie spricht:
   `"ident-bank@1"`, `"enroll-totp@1"`, `"auth-totp@1"`. Was sie nicht nennt, bekommt sie nie
   angeboten. So kann eine App ausgeliefert werden, bevor sie jedes Verfahren kennt.
2. **Aufrufen.** Ein Tool liegt unter `/tools/api/<toolId>/v<N>`
   ([ADR-51](adr/ADR-051-versionen-als-pfadsegment.md)):
   - Starten: `POST /tools/api/<toolId>/v1?channel=<channelSessionId>`, wenn `next` ein Tool ohne
     `toolSessionId` nennt. Die Antwort ist `201` mit der Tool-Sitzung in `next.toolSessionId`.
   - Eingaben: `PATCH /tools/api/<toolId>/v1/<toolSessionId>` mit genau den Feldern, die
     `stepData.missingFields` nennt.
   - Stand lesen: `GET` auf dieselbe Adresse, etwa nach einem Neustart der App.
   - Zurück zur Auswahl: `POST …/<toolSessionId>/back`; ablehnen: `DELETE …/<toolSessionId>`.
   - Jede Anfrage trägt einen frischen DPoP-Nachweis für Methode und Adresse ohne Query.
3. **Darstellen.** Je `toolId` und `step` ein Bildschirm, gefüllt aus `stepData`. Erst wenn
   `toolSessionId` feststeht, nimmt der Bildschirm Eingaben an; vorher hätte ein Absenden kein Ziel.
   Eine Form mit `kind: "failed-attempt"` zeigt den Grund des letzten Versuchs als Text; einen
   Schritt, den die App nicht kennt, behandelt sie als Fehler und nicht als leeren Bildschirm.
4. **Ergebnis übernehmen.** Jede Antwort ist eine `ChannelResponse`. Die App zeigt danach, was das
   neue `next` sagt, auch wenn das ein anderes Tool oder das Ende ist.

## 3) Bank-Ident: zur Bank und zurück

Die Weiterleitung läuft wie bei `ident-nect` ([ADR-47](adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md)):

1. **Starten mit Rücksprungadresse.** Die App gibt beim Starten `returnUri` mit: die Adresse, unter
   der das Betriebssystem nach der Bank wieder die App öffnet (Universal Link bzw. App Link). Der
   Server nimmt nur Adressen an, die mit einem freigegebenen Präfix beginnen; das trägt das Backend
   für die App in die Konfiguration ein. Ohne `returnUri` kehrt die Bank zur Web-App der Demo zurück.
2. **Zur Bank.** `stepData` nennt die Sprungadresse. Die App öffnet sie im Browser oder in der App der
   Bank. Vorher speichert sie, was sie für die Rückkehr braucht: die `channelSessionId` und die
   Tool-Sitzung. Das Betriebssystem kann die App in der Zwischenzeit beenden.
3. **Zurück.** Die Bank öffnet die Rücksprungadresse mit `?bankCaseId=…`. Die App liest die
   Kennung genau einmal, setzt den gespeicherten Kanal fort (`GET /orchestrator/api/v1/channels/<id>`)
   und schickt die Kennung mit `PATCH` an das Tool, sobald dessen Schritt wieder angezeigt wird.
4. **Was die App nicht tut:** das Ergebnis der Bank lesen oder weitergeben. Die Kennung ist nur ein
   Verweis; den Inhalt holt der Orchestrator selbst bei der Bank ab und glaubt dem Client nichts.

Bricht die Person bei der Bank ab, kommt sie ohne Kennung oder mit einer gescheiterten zurück. Den
nächsten Schritt nennt dann wie immer `next`, etwa einen neuen Versuch oder ein anderes Verfahren.

## 4) Einmalcode-App: Einrichten und Anmelden

**Einrichten (`enroll-totp`).** `stepData` nennt die `otpauth://`-Adresse und das Geheimnis.

- **Auf demselben Gerät** ist der häufige Fall: Die Authenticator-App liegt auf dem Telefon, auf dem
  auch die App läuft. Einen QR-Code kann man dort nicht scannen. Die App öffnet die `otpauth://`-Adresse
  deshalb als Link; das Betriebssystem gibt sie an die installierte Authenticator-App weiter. Ist
  keine da, zeigt die App das Geheimnis zum Abtippen.
- **Auf einem anderen Gerät** hilft ein QR-Code der Adresse. Er ist eine Zugabe, nicht der Weg.
- Danach nimmt die App den ersten Code aus der Authenticator-App entgegen und schickt ihn mit `PATCH`.
  Stimmt er nicht, kommt derselbe Schritt mit `failed-attempt` zurück.
- Das Geheimnis zeigt die App nur in diesem Schritt und speichert es nicht.

**Anmelden (`auth-totp`).** Ein Eingabefeld für den Code, ein `PATCH`. Fehlversuche zählt der Server;
sind es zu viele, antwortet er mit `423`, und die App zeigt den Text der Fehlermeldung. Ein
Wiederholen-Knopf oder ein eigener Zähler in der App ist nicht nötig.

**Ohne bekanntes Konto (`auth-totp-lookup`).** E-Mail-Adresse und Code in einem Schritt. Nur
deklarieren, wenn die App diesen Einstieg anbietet; der Orchestrator bietet das Tool dann von selbst
dort an, wo ein Konto erst gefunden werden muss.

## 5) Texte

- **Name und Hinweis eines Tools** kommen aus dem Katalog als Textverweis. Die App schreibt sie nicht
  selbst, sonst hießen dieselben Verfahren in App und Web verschieden.
- **Texte des Servers** (Fehler, Grund eines Fehlversuchs, Hinweise der Journey) kommen als
  Textverweis mit Platzhaltern und werden über das Text-Bundle aufgelöst.
- **Die eigenen Texte der Bildschirme** (Beschriftungen, Erklärungen) gehören der App und werden mit
  ihr übersetzt.

## 6) Fassungen

Eine App spricht je Tool genau eine Fassung, fest mit ihrem Build
([ADR-51](adr/ADR-051-versionen-als-pfadsegment.md), Regeln in [05-api.md](05-api.md) Abschnitte 1 und 2):

- **Neue Fassung im Backend.** Bringt das Backend `enroll-totp@2`, muss die App nichts tun. Sie
  spricht weiter Fassung 1, bis sie das Neue zeigen will. Dann setzt sie die neue Vertragsdatei
  (`api/contract/tools/enroll-totp/v2.yaml`) um, deklariert `enroll-totp@2` und ruft `/v2` auf. Ein
  Release mit der neuen Fassung geht erst hinaus, wenn der Server sie führt.
- **Gesperrte oder ausgebaute Fassung.** Sperrt der Betreiber die Fassung der App, bekommt sie das Tool
  nicht mehr angeboten; sie muss mit einem kleineren oder leeren Angebot zurechtkommen. Ein
  ausdrücklicher Hinweis „App veraltet“ ist geplant (`DPoP-demo-kkod`).
- **Neue Orchestrator-Version.** Sie ist ein Pflichtupdate. Wie der abgelöste Pfad antwortet, steht
  noch nicht fest (`DPoP-demo-gd85`); die App sollte darauf mit der Bitte um ein Update reagieren.

## 7) Testen

- **Gegen den Vertrag.** Die Beispiele in den Vertragsdateien sind echte Antworten des Servers. Sie
  eignen sich als Testdaten für jeden Bildschirm, ohne laufenden Server.
- **Gegen den Server.** Ein Durchlauf über alle Schritte gegen einen laufenden Orchestrator im
  Demomodus: Codes und TANs stehen dann im Block `demo`. Ein Test der Rückkehr von der Bank braucht
  das Öffnen der Rücksprungadresse, wie die Rückkehr von Nect in der Referenz
  (`frontend/e2e/nect-return.spec.ts`).
- **Die Deklaration.** Ein Test, dass `availableTools` nur Tools nennt, die die App darstellen kann,
  jedes mit Fassung, und dass jede deklarierte Fassung zu einem Bildschirm führt.

## 8) Checkliste

- Vertragsdateien der Tools gelesen, Typen erzeugt.
- `availableTools` um `ident-bank@1`, `enroll-totp@1`, `auth-totp@1` ergänzt (und gegebenenfalls
  `auth-totp-lookup@1`).
- Je Tool und Schritt ein Bildschirm, Eingaben erst mit `toolSessionId`, unbekannte Schritte als Fehler.
- Rücksprungadresse für die Bank im Betriebssystem registriert und ihr Präfix dem Backend genannt;
  Kanal und Tool-Sitzung überleben einen Neustart der App.
- `otpauth://` als Link geöffnet, Geheimnis zum Abtippen als Ausweg, nichts davon gespeichert.
- Texte über Katalog und Text-Bundle, eigene Texte übersetzt.
- Tests gegen Vertragsbeispiele und gegen einen laufenden Server.
