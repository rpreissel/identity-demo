# ADR-45: Die QR-Warteseite fragt im Hintergrund und schickt ihr Formular nur einmal

**Status:** umgesetzt 2026-09.

**Worum es geht.** Beim QR-Login meldet sich ein Nutzer im Browser an, indem er mit der App einen
QR-Code scannt oder einen kurzen [Pairing-Code](../glossar/glossar.md) abtippt und die Anmeldung in
der App freigibt. Solange das dauert, zeigt der Browser die Seite „Mit der App anmelden“. Diese
Seite gehört zu Keycloak, dem Anmeldeserver. Sie muss irgendwie erfahren, wann die App freigegeben
oder abgelehnt hat. Die Frage ist, wie sie das abfragt, ohne den Nutzer zu stören.

**Entscheidung.** Solange die Seite „Mit der App anmelden“ (`auth-qr`/`auth-qr-lookup`, Schritt
`waitForApp`) auf die App wartet, lädt sie sich nicht neu. Stattdessen fragt sie alle zwei Sekunden
einen eigenen Endpunkt der Keycloak-Erweiterung, ob sie noch wartet. Ihr Formular schickt sie erst
ab, wenn sich etwas geändert hat:

- die App hat freigegeben oder abgelehnt,
- die Anfrage ist abgelaufen,
- oder die Journey steht nicht mehr in diesem Schritt. (Eine Journey ist der geführte Ablauf, den
  der Nutzer gerade durchläuft.)

Das Login-Theme folgt dabei diesen Regeln.

**Warum.** Bisher schickte die Seite alle drei Sekunden ihr leeres Formular ab, um die Entscheidung
der App abzufragen. Jedes Mal baute Keycloak die ganze Seite neu auf. Das hatte drei Folgen:

- QR-Code und Pairing-Code flackerten.
- Ein Screenreader las die Seite jedes Mal von vorn vor.
- Wer den Code gerade abtippte, verlor die Stelle, an der er war.

## 1) Der Endpunkt in Keycloak

Die Seite fragt einen neuen Endpunkt in Keycloak, der nur meldet, ob noch gewartet wird:
`GET /realms/{realm}/orchestrator-qr/status?client_id=…&tab_id=…`. Er ist ein
`RealmResourceProvider` der Erweiterung (`QrWaitStatusResourceProvider`).

- **Nur die eigene Anmeldung.** Der Endpunkt findet den laufenden Anmeldeablauf auf dieselbe Weise
  wie Keycloaks eigene Seiten. Das signierte Cookie `AUTH_SESSION_ID` nennt die Sitzung, `client_id`
  und `tab_id` nennen den Durchlauf darin. Fehlt das Cookie, gibt es den Durchlauf nicht oder ist
  der Client unbekannt, antwortet er `404` ohne Inhalt. Die Adresse samt `client_id` und `tab_id`
  setzt der Renderer als Seitenattribut `statusUrl`.
- **Nur lesen.** Der Endpunkt fragt den Orchestrator mit `GET /tools/{toolSessionId}/{toolId}`.
  Das geschieht von Server zu Server, wie jeder andere Aufruf der Erweiterung. Er ändert weder den
  Anmeldeablauf noch die Journey. Weiter geht es wie bisher nur mit dem Formular.
- **Nur zwei Antworten.** Die Antwort ist `{"state":"waiting"}`, solange der Orchestrator genau
  diesen Tool-Durchlauf im Schritt `waitForApp` meldet. Sonst ist sie `{"state":"ready"}`. Auch ein
  Fehler beim Lesen ergibt `ready`. Dann meldet der folgende Formularversand, was los ist. Weder ein
  Code noch ein Konto verlassen den Endpunkt. `Cache-Control: no-store` sorgt dafür, dass kein
  Zwischenspeicher die Antwort aufbewahrt.
- **Kein CORS.** Der Endpunkt setzt keine CORS-Header. Eine fremde Webseite kann ihn zwar
  aufrufen, die Antwort aber nicht lesen.

## 2) Der Lesezugriff im Orchestrator

Den `GET` auf den Tool-Durchlauf gab es schon. Er meldete eine abgelehnte oder abgelaufene Anfrage
aber weiter als `waitForApp`, weil nur der `PATCH` das Ergebnis auswertet. Deshalb meldet der
Orchestrator beim Lesen jetzt den Schritt `closed`. Er bedeutet: Die Anfrage ist beendet, der
nächste `PATCH` meldet das Ergebnis. Der `GET` schreibt weiterhin nichts. Der API-Vertrag ändert sich
nicht, denn `next.step` ist ein freier Wert.

## 3) Die Seite

Die Seite im Browser folgt wenigen festen Regeln:

- **Nur ein ausdrückliches `waiting` lässt die Seite weiter warten.** Jede andere Antwort, auch
  `404` oder ein Serverfehler, schickt das Formular einmal ab. Erreicht eine Anfrage das Netz nicht,
  wiederholt die Seite sie nach zwei Sekunden.
- **Kein zusätzlicher Zeitgeber.** Jede Änderung ist über den Endpunkt sichtbar, auch dass die
  Anfrage nach fünf Minuten abläuft. Ist der Endpunkt selbst gestört, antwortet er nicht mit
  `waiting`. Die Seite fällt dann auf das alte Verhalten zurück: Sie lädt neu und fragt dann wieder.
- **„Abbrechen“ beendet das Fragen.** So kann keine späte Antwort das Formular ein zweites Mal
  abschicken.
- **Nichts verschiebt sich.** QR-Code und Pairing-Code bleiben unverändert stehen, bis die Seite
  wechselt.

Das Login-Theme setzt die Regeln im Modul `qrStatusPoll.ts` um, mit eigenen Tests
(bis [ADR-57](ADR-057-keycloakify-einziges-login-theme.md) auch die FreeMarker-Vorlage als kleines Skript).

## Erwogene Alternativen

- **Weiter per Formular fragen, nur seltener.** Verworfen: Das Flackern bliebe, nur seltener. Und
  die Freigabe käme später an.
- **Den Browser direkt beim Orchestrator fragen lassen.** Verworfen: Im Web-Zugang spricht der
  Browser nie mit dem Orchestrator ([05-api.md](../05-api.md) Abschnitt 3b). Er bräuchte dafür eine
  eigene Berechtigung und eine CORS-Freigabe.
- **Server-Sent Events oder WebSocket statt Abfragen.** Verworfen: Keycloak müsste dafür
  Verbindungen offen halten. Für eine Entscheidung, die nach einigen Sekunden fällt, reicht eine
  Frage alle zwei Sekunden.
- **Den `PATCH` im Hintergrund schicken.** Verworfen: Der `PATCH` bringt den Ablauf einen Schritt
  weiter, zählt Versuche und müsste die nächste Seite darstellen. Das ist Aufgabe des Formulars.
