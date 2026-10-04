# ADR-45: Die QR-Warteseite fragt im Hintergrund und schickt ihr Formular nur einmal

**Status:** umgesetzt 2026-09.

**Entscheidung.** Solange die Seite „Mit der App anmelden“ (`auth-qr`/`auth-qr-lookup`, Schritt
`waitForApp`) auf die App wartet, lädt sie sich nicht neu. Sie fragt alle zwei Sekunden einen
eigenen Endpunkt der Keycloak-Erweiterung, ob sie noch wartet, und schickt ihr Formular erst ab,
wenn sich etwas geändert hat: Die App hat freigegeben oder abgelehnt, die Anfrage ist abgelaufen,
oder die Journey ist woanders. Beide Login-Themes tun das nach denselben Regeln.

**Warum.** Die Seite schickte ihr leeres Formular alle drei Sekunden ab, um die Entscheidung der
App abzufragen. Jedes Mal baute Keycloak die ganze Seite neu auf. QR-Code und Pairing-Code
flackerten, ein Screenreader las die Seite von vorn, und wer den Code gerade abtippte, verlor die
Stelle.

## 1) Der Endpunkt in Keycloak

`GET /realms/{realm}/orchestrator-qr/status?client_id=…&tab_id=…`, ein `RealmResourceProvider`
der Erweiterung (`QrWaitStatusResourceProvider`).

- **Nur die eigene Anmeldung.** Er findet den Anmeldeablauf wie Keycloaks eigene Seiten: das
  signierte Cookie `AUTH_SESSION_ID` nennt die Sitzung, `client_id` und `tab_id` den Durchlauf
  darin. Ohne Cookie, ohne Durchlauf oder bei unbekanntem Client antwortet er `404` ohne Inhalt.
  Die Adresse samt `client_id` und `tab_id` setzt der Renderer als Seitenattribut `statusUrl`.
- **Nur lesen.** Er fragt den Orchestrator mit `GET /tools/{toolSessionId}/{toolId}`, von Server zu
  Server wie jeder andere Aufruf der Erweiterung, und ändert weder den Anmeldeablauf noch die
  Journey. Weiter geht es nur mit dem Formular, wie bisher.
- **Nur zwei Antworten.** `{"state":"waiting"}`, solange der Orchestrator genau diese Tool-Sitzung
  im Schritt `waitForApp` nennt, sonst `{"state":"ready"}`. Auch ein Fehler beim Lesen ist
  `ready`: Dann meldet der Formularversand, was los ist. Kein Code und kein Konto verlassen den
  Endpunkt, und `Cache-Control: no-store` hält die Antwort aus jedem Zwischenspeicher.
- **Kein CORS.** Der Endpunkt setzt keine CORS-Header. Eine fremde Seite kann ihn zwar aufrufen,
  die Antwort aber nicht lesen.

## 2) Der Lesezugriff im Orchestrator

Den `GET` auf die Tool-Sitzung gab es schon. Er meldete aber eine abgelehnte oder abgelaufene
Anfrage weiter als `waitForApp`, weil nur der `PATCH` das Ergebnis auswertet. Dafür gibt es beim
Lesen jetzt den Schritt `closed`: Die Anfrage ist beendet, der nächste `PATCH` meldet das Ergebnis.
Der `GET` bleibt ohne Schreibzugriff, und der Vertrag ändert sich nicht, denn `next.step` ist ein
freier Wert.

## 3) Die Seite

- **Nur ein ausdrückliches `waiting` hält die Seite.** Jede andere Antwort, auch `404` oder ein
  Serverfehler, schickt das Formular einmal ab. Eine Anfrage, die das Netz nicht erreicht, wird
  nach zwei Sekunden wiederholt.
- **Kein zusätzlicher Zeitgeber.** Jede Änderung ist über den Endpunkt sichtbar, auch der Ablauf
  der Anfrage nach fünf Minuten. Ist der Endpunkt selbst gestört, antwortet er nicht mit `waiting`,
  und die Seite fällt auf das alte Verhalten zurück: Sie lädt neu und fragt dann wieder.
- **„Abbrechen“ beendet das Fragen,** damit keine späte Antwort das Formular ein zweites Mal
  abschickt.
- **Nichts verschiebt sich.** QR-Code und Pairing-Code bleiben stehen, bis die Seite wechselt.

Die FreeMarker-Vorlage hat die Regeln als kleines Skript, das Keycloakify-Theme als Modul
`qrStatusPoll.ts` mit eigenen Tests.

## Erwogene Alternativen

- **Weiter per Formular fragen, nur seltener.** Verworfen: Das Flackern bliebe, nur seltener, und
  die Freigabe käme später an.
- **Den Browser direkt beim Orchestrator fragen lassen.** Verworfen: Im Web-Zugang spricht der
  Browser nie mit dem Orchestrator ([05-api.md](../05-api.md) Abschnitt 3b). Er bräuchte dafür eine
  eigene Berechtigung und eine CORS-Freigabe.
- **Server-Sent Events oder WebSocket statt Abfragen.** Verworfen: Keycloak müsste Verbindungen
  offen halten, und für eine Entscheidung in einigen Sekunden reicht eine Frage alle zwei Sekunden.
- **Den `PATCH` im Hintergrund schicken.** Verworfen: Er treibt den Ablauf weiter, zählt Versuche und
  müsste die nächste Seite zeichnen. Das ist Aufgabe des Formulars.
