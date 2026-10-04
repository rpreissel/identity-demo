# Verfahren `qr`

Anmelden im Browser, bestätigt mit der App: Der Browser zeigt einen QR-Code und einen Pairing-Code,
eine schon angemeldete App bestätigt die Anfrage und zeigt einen Bestätigungscode, den der Nutzer
im Browser eintippt. Erst damit ist der Browser angemeldet.

## Tools

| toolId | Rolle | Fassungen | Faktoren |
|---|---|---|---|
| `enroll-qr` | `ENROLLMENT`, `optInOnly` | 1 | `{}` |
| `auth-qr` | `KNOWN_ACCOUNT_AUTH` (Name „Mit App bestätigen“) | 1 | `{possession,knowledge}` |
| `auth-qr-lookup` | `ACCOUNT_LOOKUP_AUTH` (Name „Mit App anmelden“) | 1 | `{possession,knowledge}` |
| `approve-qr` | `PEER_APPROVAL` | 1 | `{}` |

Höchstens `loa2`; ein aktiver Eintrag je Konto (`allowsMultipleInstances = false`). Deklariert in
`tools/auth_qr/QrToolModule.kt`. Die Zustimmung liegt in `auth_qr.enrollment`. Eine Anfrage bleibt
fünf Minuten offen (unten, „Sicherheit des Pairing-Codes“).

## Besonderheiten

- `enroll-qr`/`auth-qr`/`auth-qr-lookup` folgen demselben Muster wie `sms`, `password` und `email`:
  einrichten, anmelden und anmelden über die E-Mail-Adresse. Eine Besonderheit gibt es:
  `enroll-qr` ist eine reine Zustimmung (Opt-in) ohne Geheimnis (`enroll("enroll-qr", optInOnly = true)`,
  damit `factorTypes = {}`); ob diese Zustimmung vorliegt, prüft erst `approve-qr`.
- `auth-qr`/`auth-qr-lookup` deklarieren `factorTypes = {possession, knowledge}`. Das Handy, das
  die Anmeldung bestätigt, muss laut `ConfirmPeerLoginStrategy.gate()` vorher selbst frisch loa2
  nachgewiesen haben. Das ist MFA aus einem einzigen Verfahren wie bei `ident-eid` und passt zu
  `maxAcr=loa2`.
- `approve-qr` hat die Rolle `ToolRole.PEER_APPROVAL`, denn keine der übrigen Rollen passt auf
  „bestätigt, was jemand anderes tut". Es trägt nichts zum Niveau des eigenen Kanals bei und wird
  nur ausdrücklich per Intent gestartet ([03-tool-architektur.md](../03-tool-architektur.md)
  Abschnitt 4).

## Die App bestätigt: `approve-qr`

Der Einstieg ist der Intent `CONFIRM_PEER_LOGIN` mit seiner Prüfung von Konto, Niveau und Frische
des Nachweises ([05-api.md](../05-api.md) Abschnitt 3a, „Peer-Login bestätigen“, Schritte 1 bis 3).
Danach:

4. `approve-qr` starten:
   - `POST .../tools/approve-qr` (ohne Inhalt) →
     `stepData={"kind":"missing-fields","missingFields":["pairingCode"]}`.
   - `PATCH .../approve-qr` mit `{"pairingCode":"..."}` (aus dem QR-Code bzw. über den
     Demo-Link vorbelegt, [Frontend](../10-frontend.md)) → bei einer gültigen, noch offenen Anfrage
     `next.step="confirm"`. Bei einem unbekannten, abgelaufenen oder schon entschiedenen Code kommt
     `stepData.error`; der Schritt bleibt auf `input`, und es gelten die üblichen Regeln für weitere
     Versuche.
   - `PATCH .../approve-qr` mit `{"decision":"accept"}` bzw. `{"decision":"reject"}`. Hat das
     Konto kein aktives `enroll-qr`, liefert `accept` `stepData.error` („QR-Login ist für dieses
     Konto nicht aktiviert").
5. Nach erfolgreichem `accept`: `next.step="showCode"` mit
   `stepData={"kind":"qr-pairing","confirmationCode":"482913"}`. Diesen **Bestätigungscode** tippt
   der Nutzer in den wartenden Browser; erst damit ist der Browser angemeldet (Code in
   Gegenrichtung, unten „Sicherheit des Pairing-Codes“). Der Code steht nur in dieser einen Antwort,
   gespeichert wird sein Hash; nach einem Neuladen zeigt `showCode` ihn nicht mehr.
   `PATCH .../approve-qr` mit `{"decision":"done"}` beendet den Schritt:
   `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`. War der Kanal
   vor diesem Aufruf noch nicht `AUTHENTICATED`, fragt die Antwort vorher per `Prompt` („Jetzt
   abmelden?"), ob der Kanal angemeldet bleiben soll. `reject` liefert stattdessen `stepData.error`
   („Vom Nutzer abgelehnt"); ein eigenes `ToolOutcome` braucht die Ablehnung nicht.

## Der Browser wartet: `auth-qr` / `auth-qr-lookup`

Die Web-Seite selbst (`auth-qr`/`auth-qr-lookup`) hat zwei Schritte. In `waitForApp` liest sie
den Zustand im Hintergrund, ohne neu zu laden. Erst wenn sich
etwas geändert hat, schickt sie den leeren `PATCH`; ein leerer `PATCH` zählt nicht gegen die
Versuchs- oder Login-Sperre. Nach der Freigabe in der App liefert er
`next.step="enterCode"` (`missingFields=["confirmationCode"]`). Der `GET` auf dieselbe Tool-Sitzung
entscheidet nichts: Er meldet `waitForApp`, nach der Freigabe `enterCode` und für eine abgelehnte
oder abgelaufene Anfrage `closed`. Das Ergebnis wertet erst der nächste `PATCH` aus. `PATCH` mit
`{"confirmationCode":"..."}` liefert beim richtigen Code `Completed.Authenticated`, bei einem
falschen `Failed`; nach drei falschen Codes ist die Anfrage verbrannt. Bei `DENIED` oder nach Ablauf
kommt ebenfalls `Failed`.

### Die Warteseite im Browser

Der Browser zeigt QR-Code und Pairing-Code und wartet, bis die App entscheidet. Der Ablauf auf der
Seite:

1. Keycloak zeigt `tool-qr-wait` im Schritt `waitForApp`, mit `statusUrl` als Seitenattribut.
2. Die Seite fragt alle zwei Sekunden `statusUrl` ab. Keycloak findet über das Cookie der
   Anmeldung die laufende Tool-Sitzung und liest sie beim Orchestrator mit `GET`. Solange die
   Antwort `waiting` ist, bleibt die Seite unverändert stehen.
3. Die App gibt frei oder lehnt ab, oder die Anfrage läuft nach fünf Minuten ab. Der `GET` meldet
   jetzt `enterCode` bzw. `closed`, der Endpunkt antwortet `ready`.
4. Die Seite schickt ihr leeres Formular einmal ab. Erst dieser `PATCH` wertet das Ergebnis aus:
   Nach einer Freigabe folgt das Feld für den Bestätigungscode, nach Ablehnung oder Ablauf ein
   Fehlschlag, und die Journey entscheidet, wie es weitergeht.

„Abbrechen“ beendet das Fragen und lehnt das Tool ab (`orchestrator_abandon`). Der Endpunkt, den die
Seite abfragt (`GET /realms/{realm}/orchestrator-qr/status`), liegt in Keycloak und ist in
[05-api.md](../05-api.md) Abschnitt 3b beschrieben. Warum die Seite
nicht mehr per Formular fragt und welche Regeln die Abfrage hat, steht in
[ADR-45](../adr/ADR-045-qr-warteseite-fragt-im-hintergrund.md).

## Sicherheit des Pairing-Codes

Der Schritt `input` von `approve-qr` nimmt einen `pairingCode` entgegen, den der Nutzer
eingibt oder den ein Deep-Link vorausfüllt ([`CONFIRM_PEER_LOGIN`](../journeys/confirm-peer-login.md)):

- **Schutz davor, einen fremden QR-Code zu bestätigen – Code in Gegenrichtung:** Die Freigabe in
  der App meldet den Browser noch nicht an. Sie erzeugt einen sechsstelligen **Bestätigungscode**,
  den nur die App anzeigt und den der Nutzer in den wartenden Browser tippt; erst dann ist der
  Browser angemeldet (`QrLoginBrowserSide`). Ein Angreifer, der dem Opfer seinen eigenen
  Pairing-Code schickt (per Link oder als QR-Bild), bekommt damit nichts: Das Opfer müsste den Code
  in den Browser des Angreifers tippen oder ihn ausdrücklich weitergeben; die App warnt davor.
  Ein Vergleichscode, den beide Seiten nur anzeigen und den man mit dem Auge vergleicht, reicht
  dafür nicht: Den kann der Angreifer einfach mit in seine Nachricht schreiben.
  **Nicht** geschützt ist gegen ein Opfer, das den Bestätigungscode auf Nachfrage selbst herausgibt.
- **Bestätigungscode:** gespeichert nur als Hash, im Klartext genau einmal an die App ausgeliefert.
  Nach der Freigabe hat der Browser zwei Minuten Zeit; nach drei falschen Codes ist die Anfrage
  verbrannt (`EXPIRED`, `countWrongConfirmation`). Das Versuchsbudget der Journey (3) greift
  zusätzlich.
- **Unteilbare Zustandswechsel:** Freigabe, Ablehnung und Abschluss schreiben nur unter einer
  Bedingung (`approveIfPending`/`denyIfPending`: `status = 'PENDING'` und nicht abgelaufen;
  `completeIfConfirmed`: `status = 'APPROVED'`, richtiger Hash, nicht abgelaufen). Wird keine Zeile
  getroffen, war die Anfrage bereits entschieden, abgelaufen oder der Code falsch. So können nie
  zwei Konten gleichzeitig als `resolvingAccountId` eingetragen werden, und ein Code meldet nie zwei
  Browser an.
- **Zufallsgehalt des `pairingCode`:** 8 Zeichen aus einem Alphabet mit wenig Verwechslungsgefahr
  (ähnlich Crockford-Base32, ohne `I`, `L`, `O` und `U`), etwa 40 Bit. Das ist bewusst weniger als
  bei einem reinen API-Token, weil ein Mensch den Code fehlerfrei abschreiben können muss.

**Noch offen:** Weil die Eingabe von Hand ein regulärer Weg ist, bräuchte der Schritt `input` einen
eigenen Zähler für fehlgeschlagene Suchen nach einem `pairingCode`, etwa je IP-Adresse oder ohne
Bezug auf ein Konto. `RateLimitRecord` ([07-betrieb.md](../07-betrieb.md) Abschnitt 4) hilft hier nicht, weil noch kein Konto bekannt
ist. Das ist derzeit **nicht umgesetzt**.

`QrLoginRequest.expiresAt` (5 Minuten, `QR_LOGIN_TTL`) orientiert sich an den Laufzeiten der
TANs (`enroll-sms`/`auth-sms`). Abgelaufene Zeilen sind beim Lesen wirkungslos, und
`AuthQrRetentionJob` räumt sie auf ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).

## Fehlerfälle

- Unbekannter, abgelaufener oder schon entschiedener Pairing-Code bei `approve-qr` ->
  `stepData.error`, der Schritt bleibt auf `input`.
- `accept` ohne aktives `enroll-qr` -> `stepData.error`.
- Falscher Bestätigungscode im Browser -> `Failed`; nach drei falschen Codes ist die Anfrage
  verbrannt. Abgelehnte oder abgelaufene Anfrage -> `Failed`.

## In der Demo

Der Pairing-Code lässt sich über einen Demo-Link in der App vorbelegen. Bei `auth-qr-lookup` steht
die gefundene `accountId` im `demo`-Objekt.
