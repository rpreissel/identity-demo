# Verfahren `qr`

**Was es ist:** Der Nutzer meldet sich im Browser an und bestätigt die Anmeldung mit der App auf
seinem Smartphone. Der Browser zeigt einen QR-Code und daneben einen Pairing-Code. Das ist eine
kurze Zeichenfolge, die man statt des QR-Codes in der App eintippen kann. In der App ist der Nutzer
schon angemeldet. Dort scannt er den QR-Code (oder tippt den Pairing-Code ein) und bestätigt die
Anfrage. Danach zeigt die App einen Bestätigungscode, den der Nutzer im Browser eintippt. Erst damit
ist der Browser angemeldet.

**Wozu es dient:** Der Nutzer kann sich mit Hilfe der App auf der Website anmelden. Dafür muss er
das Verfahren vorher in seinem Konto einschalten. Es erreicht höchstens das Niveau `loa2`.

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat vier Tools:

- `enroll-qr` schaltet das Verfahren im Konto ein.
- `auth-qr` meldet im Browser ein bekanntes Konto an.
- `auth-qr-lookup` meldet im Browser an, ohne dass das Konto vorher bekannt ist.
- `approve-qr` läuft in der App und bestätigt die Anmeldung im Browser.

| toolId | Rolle | Fassungen | Faktoren |
|---|---|---|---|
| `enroll-qr` | `ENROLLMENT`, `optInOnly` | 1 | `{}` |
| `auth-qr` | `KNOWN_ACCOUNT_AUTH` (Name „Mit App bestätigen“) | 1 | `{possession,knowledge}` |
| `auth-qr-lookup` | `ACCOUNT_LOOKUP_AUTH` (Name „Mit App anmelden“) | 1 | `{possession,knowledge}` |
| `approve-qr` | `PEER_APPROVAL` | 1 | `{}` |

Das Verfahren liefert höchstens das Niveau `loa2`. Je Konto gibt es höchstens einen aktiven
Eintrag (`allowsMultipleInstances = false`). Deklariert ist das Verfahren in
`tools/auth_qr/QrToolModule.kt`. Die Zustimmung des Nutzers liegt in `auth_qr.enrollment`. Eine
Anfrage bleibt fünf Minuten offen (unten, „Sicherheit des Pairing-Codes“).

## Besonderheiten

- `enroll-qr`, `auth-qr` und `auth-qr-lookup` folgen demselben Muster wie `sms`, `password` und
  `email`: einrichten, anmelden und anmelden über die E-Mail-Adresse. Eine Besonderheit gibt es:
  `enroll-qr` ist eine reine Zustimmung (Opt-in) ohne Geheimnis
  (`enroll("enroll-qr", optInOnly = true)`, damit `factorTypes = {}`). Ob diese Zustimmung
  vorliegt, prüft erst `approve-qr`.
- `auth-qr` und `auth-qr-lookup` deklarieren `factorTypes = {possession, knowledge}`, also Besitz
  und Wissen. Denn das Handy, das die Anmeldung bestätigt, muss laut
  `ConfirmPeerLoginStrategy.gate()` vorher selbst frisch loa2 nachgewiesen haben. Damit erbringt ein
  einziges Verfahren zwei Faktortypen, wie `ident-eid`. Das passt zu `maxAcr=loa2`. Wie sich das zu
  den Begriffen des externen Glossars verhält, steht im
  [Abgleich](../glossar/abgleich-externes-glossar.md), Abschnitt 4.
- `approve-qr` hat die Rolle `ToolRole.PEER_APPROVAL`, denn keine der übrigen Rollen passt auf
  „bestätigt, was jemand anderes tut". Das Tool erhöht nicht das Niveau des eigenen Kanals,
  also der Verbindung der App zum Orchestrator. Es wird nur ausdrücklich per **Intent** gestartet,
  also mit dem Anliegen, mit dem der Nutzer kommt ([03-tool-architektur.md](../03-tool-architektur.md)
  Abschnitt 4).

## Die App bestätigt: `approve-qr`

Dieser Abschnitt beschreibt die Seite der App. Der Einstieg ist der Intent `CONFIRM_PEER_LOGIN`.
Er prüft zuerst Konto, Niveau und wie frisch der Nachweis ist ([05-api.md](../05-api.md)
Abschnitt 3a, „Peer-Login bestätigen“, Schritte 1 bis 3). Danach geht es so weiter:

4. `approve-qr` starten:
   - `POST .../tools/approve-qr` (ohne Inhalt) →
     `stepData={"kind":"missing-fields","missingFields":["pairingCode"]}`.
   - `PATCH .../approve-qr` mit `{"pairingCode":"..."}`. Der Code kommt aus dem QR-Code oder ist
     über den Demo-Link vorbelegt ([Frontend](../10-frontend.md)). Bei einer gültigen, noch offenen
     Anfrage folgt `next.step="confirm"`. Bei einem unbekannten, abgelaufenen oder schon
     entschiedenen Code kommt `stepData.error`. Der Schritt bleibt dann auf `input`, und es gelten
     die üblichen Regeln für weitere Versuche.
   - `PATCH .../approve-qr` mit `{"decision":"accept"}` bzw. `{"decision":"reject"}`. Hat das
     Konto kein aktives `enroll-qr`, liefert `accept` `stepData.error` („QR-Login ist für dieses
     Konto nicht aktiviert").
5. Nach erfolgreichem `accept` folgt `next.step="showCode"` mit
   `stepData={"kind":"qr-pairing","confirmationCode":"482913"}`. Diesen **Bestätigungscode** tippt
   der Nutzer in den wartenden Browser. Erst damit ist der Browser angemeldet. Der Code geht also in
   die Gegenrichtung, von der App zum Browser (unten „Sicherheit des Pairing-Codes“). Der Code steht
   nur in dieser einen Antwort. Gespeichert wird nur sein Hash. Nach einem Neuladen zeigt
   `showCode` ihn nicht mehr.
   `PATCH .../approve-qr` mit `{"decision":"done"}` beendet den Schritt:
   `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`. War der Kanal
   vor diesem Aufruf noch nicht `AUTHENTICATED`, fragt die Antwort vorher per `Prompt` („Jetzt
   abmelden?"), ob der Kanal angemeldet bleiben soll. `reject` liefert stattdessen `stepData.error`
   („Vom Nutzer abgelehnt"). Ein eigenes `ToolOutcome` braucht die Ablehnung nicht.

## Der Browser wartet: `auth-qr` / `auth-qr-lookup`

Dieser Abschnitt beschreibt die Seite des Browsers. Die Web-Seite (`auth-qr`/`auth-qr-lookup`) hat
zwei Schritte.

- **`waitForApp`**: Die Seite liest den Zustand im Hintergrund, ohne neu zu laden. Erst wenn sich
  etwas geändert hat, schickt sie den leeren `PATCH`. Ein leerer `PATCH` zählt nicht gegen die
  Versuchs- oder Login-Sperre. Nach der Freigabe in der App liefert er `next.step="enterCode"`
  (`missingFields=["confirmationCode"]`).
- **`enterCode`**: `PATCH` mit `{"confirmationCode":"..."}` liefert beim richtigen Code
  `Completed.Authenticated` und bei einem falschen `Failed`. Nach drei falschen Codes ist die
  Anfrage endgültig ungültig. Bei `DENIED` oder nach Ablauf kommt ebenfalls `Failed`.

Der `GET` auf dieselbe Tool-Sitzung entscheidet nichts. Er meldet nur den Stand: `waitForApp`, nach
der Freigabe `enterCode` und für eine abgelehnte oder abgelaufene Anfrage `closed`. Das Ergebnis
wertet erst der nächste `PATCH` aus.

### Die Warteseite im Browser

Der Browser zeigt QR-Code und Pairing-Code und wartet, bis die App entscheidet. Die Seite führt
**Keycloak**, das auf der Website die Anmeldung übernimmt. Der Ablauf auf der Seite:

1. Keycloak zeigt `tool-qr-wait` im Schritt `waitForApp`, mit `statusUrl` als Seitenattribut.
2. Die Seite fragt alle zwei Sekunden `statusUrl` ab. Keycloak findet über das Cookie der
   Anmeldung die laufende Tool-Sitzung und liest sie beim Orchestrator mit `GET`. Solange die
   Antwort `waiting` ist, bleibt die Seite unverändert stehen.
3. Die App gibt frei oder lehnt ab, oder die Anfrage läuft nach fünf Minuten ab. Der `GET` meldet
   jetzt `enterCode` bzw. `closed`, und der Endpunkt antwortet `ready`.
4. Die Seite schickt ihr leeres Formular einmal ab. Erst dieser `PATCH` wertet das Ergebnis aus:
   Nach einer Freigabe folgt das Feld für den Bestätigungscode. Nach Ablehnung oder Ablauf folgt ein
   Fehlschlag, und die Journey entscheidet, wie es weitergeht.

„Abbrechen“ beendet das Fragen und lehnt das Tool ab (`orchestrator_abandon`). Der Endpunkt, den die
Seite abfragt (`GET /realms/{realm}/orchestrator-qr/status`), liegt in Keycloak. Er ist in
[05-api.md](../05-api.md) Abschnitt 3b beschrieben. Warum die Seite nicht mehr per Formular fragt
und welche Regeln die Abfrage hat, steht in
[ADR-45](../adr/ADR-045-qr-warteseite-fragt-im-hintergrund.md).

## Sicherheit des Pairing-Codes

Der Schritt `input` von `approve-qr` nimmt einen `pairingCode` entgegen. Der Nutzer gibt ihn ein,
oder ein Deep-Link füllt ihn vor ([`CONFIRM_PEER_LOGIN`](../journeys/confirm-peer-login.md)). Die
folgenden Regeln schützen diesen Ablauf:

- **Schutz davor, einen fremden QR-Code zu bestätigen – Code in Gegenrichtung:** Die Freigabe in
  der App meldet den Browser noch nicht an. Sie erzeugt einen sechsstelligen **Bestätigungscode**,
  den nur die App anzeigt. Der Nutzer tippt ihn in den wartenden Browser. Erst dann ist der Browser
  angemeldet (`QrLoginBrowserSide`).
  Ein Angreifer könnte dem Opfer seinen eigenen Pairing-Code schicken (per Link oder als QR-Bild).
  Damit erreicht er nichts: Das Opfer müsste den Bestätigungscode in den Browser des Angreifers
  tippen oder ihn ausdrücklich weitergeben. Die App warnt davor.
  Ein Vergleichscode, den beide Seiten nur anzeigen und den man mit dem Auge vergleicht, reicht
  dafür nicht. Den kann der Angreifer einfach mit in seine Nachricht schreiben.
  **Nicht** geschützt ist der Ablauf gegen ein Opfer, das den Bestätigungscode auf Nachfrage selbst
  herausgibt.
- **Bestätigungscode:** Er wird nur als Hash gespeichert und im Klartext genau einmal an die App
  ausgeliefert. Nach der Freigabe hat der Browser zwei Minuten Zeit. Nach drei falschen Codes ist
  die Anfrage endgültig ungültig (`EXPIRED`, `countWrongConfirmation`). Das Versuchsbudget der
  Journey (3) gilt zusätzlich.
- **Unteilbare Zustandswechsel:** Freigabe, Ablehnung und Abschluss schreiben nur, wenn eine
  Bedingung erfüllt ist:
  - `approveIfPending`/`denyIfPending`: `status = 'PENDING'` und nicht abgelaufen;
  - `completeIfConfirmed`: `status = 'APPROVED'`, richtiger Hash, nicht abgelaufen.

  Wird keine Zeile getroffen, war die Anfrage bereits entschieden oder abgelaufen, oder der Code war
  falsch. So können nie zwei Konten gleichzeitig als `resolvingAccountId` eingetragen werden, und
  ein Code meldet nie zwei Browser an.
- **Zufallsgehalt des `pairingCode`:** Der Code hat 8 Zeichen aus einem Alphabet mit wenig
  Verwechslungsgefahr (ähnlich Crockford-Base32, ohne `I`, `L`, `O` und `U`), das sind etwa 40 Bit.
  Das ist bewusst weniger als bei einem reinen API-Token, weil ein Mensch den Code fehlerfrei
  abschreiben können muss.

**Noch offen:** Die Eingabe von Hand ist ein regulärer Weg. Deshalb bräuchte der Schritt `input`
einen eigenen Zähler für fehlgeschlagene Suchen nach einem `pairingCode`, etwa je IP-Adresse oder
ohne Bezug auf ein Konto. `RateLimitRecord` ([07-betrieb.md](../07-betrieb.md) Abschnitt 4) hilft
hier nicht, weil noch kein Konto bekannt ist. Das ist derzeit **nicht umgesetzt**.

`QrLoginRequest.expiresAt` (5 Minuten, `QR_LOGIN_TTL`) orientiert sich an den Laufzeiten der
TANs (`enroll-sms`/`auth-sms`). Abgelaufene Zeilen sind beim Lesen wirkungslos, und
`AuthQrRetentionJob` räumt sie auf ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).

## Fehlerfälle

- Unbekannter, abgelaufener oder schon entschiedener Pairing-Code bei `approve-qr` ->
  `stepData.error`, der Schritt bleibt auf `input`.
- `accept` ohne aktives `enroll-qr` -> `stepData.error`.
- Falscher Bestätigungscode im Browser -> `Failed`. Nach drei falschen Codes ist die Anfrage
  endgültig ungültig.
- Abgelehnte oder abgelaufene Anfrage -> `Failed`.

## In der Demo

Der Pairing-Code lässt sich über einen Demo-Link in der App vorbelegen. Bei `auth-qr-lookup` steht
die gefundene `accountId` im `demo`-Objekt.
