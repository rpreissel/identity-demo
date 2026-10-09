# ADR-59: Die Nachweise einer Keycloak-Sitzung liegen im Orchestrator

**Status:** entschieden und umgesetzt 2026-10-09 (Issue `DPoP-demo-2n71`).
Folgt auf [ADR-58](ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md), hängt mit
[ADR-43](ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md) zusammen.

**Worum es geht.** Auf der Website legt jeder Durchlauf durch Keycloak einen neuen
[Kanal](../glossar/glossar.md) beim Orchestrator an, etwa ein Step-up nach der Anmeldung. Der neue
Kanal soll übernehmen, was frühere Durchläufe derselben
[Keycloak-Sitzung](../glossar/glossar.md) schon nachgewiesen haben. Sonst müsste der Nutzer alles
noch einmal beweisen.

Bisher lief das über ein signiertes Token, RestoreData:

- Am Ende jedes erfolgreichen Durchlaufs rief die Extension `GET .../restore-data` auf. Der
  Orchestrator gab die Nachweise des Kanals als Token zurück, mit HMAC signiert, gebunden an die
  `kcSessionId` und 12 Stunden gültig (`RestoreDataCodec`). Das Geheimnis dafür würfelte jeder
  Prozess beim Start neu.
- Keycloak legte das Token als Notiz in seiner Nutzersitzung ab (`orchestrator_restore_data`).
- Der nächste Durchlauf schickte es einmal als `restoreData` im ersten `PATCH` zurück.

Ein Tab, der später fertig wurde, überschrieb die Notiz. Liefen zwei Tabs derselben Sitzung
parallel, ging so der Nachweis des einen verloren. Die Frage war, wo der Stand einer Sitzung liegen
soll.

**Entscheidung.** Der Orchestrator legt die Nachweise selbst ab, in der Tabelle
`orchestrator.keycloak_session_evidence`. Sie hat eine Zeile je Keycloak-Sitzung und Verfahren
(Schlüssel `kc_session_id`, `method`). Im Einzelnen:

- **Ende eines Durchlaufs.** Die Extension ruft `POST .../kc/channels/{id}/flow-end?kcSessionId=…`
  auf, mit `sessionExpiresAt` wie bisher ([05-api.md](../05-api.md) Abschnitt 3b). Der Orchestrator
  kürzt die Frist des Kanals auf das Ende der Sitzung (ADR-43). Dann schreibt er jedes Verfahren aus
  den Nachweisen des Kanals: Fehlt die Zeile, legt er sie an. Danach ersetzt er sie nur durch einen
  jüngeren Nachweis (`updateIfYounger`). Alle Zeilen der Sitzung enden mit `sessionExpiresAt`.
  Nennt Keycloak keinen Wert, enden sie nach 12 Stunden. Ein Kanal einer Einladung legt nichts ab
  ([ADR-48](ADR-048-vorgangszugang-mit-einmalkennwort.md)).
- **Nächster Durchlauf.** `OrchestratorResumeAuthenticator` schickt im ersten `PATCH` nur die
  `kcSessionId`. Der Orchestrator liest die Zeilen der Sitzung, die noch nicht abgelaufen sind. Nennt
  Keycloak ein anderes Konto als die Zeilen, antwortet er mit `409`. Ein neuer Kanal übernimmt die
  Nachweise mit `Action.ApplyRestoredEvidence`, vor der ersten Entscheidung der Journey
  ([04-orchestrierung.md](../04-orchestrierung.md) Abschnitt 8). Jeder Nachweis behält seinen
  Zeitpunkt.
- **Löschen.** Die Abmeldung in Keycloak löscht alle Zeilen der Sitzung. `RetentionJob` löscht
  abgelaufene Zeilen. Der Widerruf eines Verfahrens löscht seine Zeilen in allen Sitzungen des
  Kontos. Das Löschen des Kontos löscht alle seine Zeilen.

## Warum

**Das Token brachte kein zusätzliches Vertrauen.** Es war an die `kcSessionId` gebunden. Beide
kamen von Keycloak, in einer Anfrage mit signierter Assertion (Peer-Auth,
[ADR-7](ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md)). Keycloak hielt das Token
ohnehin selbst. Wer Keycloaks Assertions fälschen kann, kann also auch ein gespeichertes Token
einreichen. Die Signatur schützte damit nur vor etwas, das Peer-Auth schon ausschließt. Die
`kcSessionId` allein trägt dieselbe Aussage. Die Prüfung auf das Konto bleibt: Gehören die Zeilen
einem anderen Konto, gibt es `409`.

**Parallele Tabs verlieren nichts mehr.** Jeder Tab schreibt seine eigenen Verfahren. Ein Tab mit
Passwort und einer mit SMS ergeben zwei Zeilen, und der nächste Durchlauf sieht beide. Ein anderer
Browser hat eine andere `kcSessionId` und damit eigene Zeilen.

**Eine Zeile je Sitzung und Verfahren statt eines Datensatzes je Sitzung.** Enden zwei Tabs
gleichzeitig, darf keiner den anderen überschreiben. Ein Datensatz je Sitzung bräuchte Lesen,
Ändern und Schreiben, und dazwischen gewinnt der Letzte. Mit einer Zeile je Verfahren genügt eine
Anweisung je Verfahren. `updateIfYounger` ist ein einziges `UPDATE` mit der Bedingung
`proven_at <= :provenAt`. Seine Zeilensperre ordnet zwei gleichzeitige Tabs. Die fehlende Zeile legt
`KeycloakSessionEvidenceInitializer` in einer eigenen Transaktion an, wie
`RateLimitRecordInitializer`. Ein Konflikt auf dem Schlüssel heißt dann nur: Die Zeile gibt es
schon, das `UPDATE` entscheidet. Ein `MERGE` ginge nicht, denn H2 führt es nicht atomar aus.

**Die Nachweise leben so lange wie die Sitzung.** Ein Kanal lebt höchstens 30 Minuten. Die Zeilen
hängen an keinem Kanal. Sie enden mit der Keycloak-Sitzung (ADR-43), mit ihrer Abmeldung oder mit
dem Ablauf. Für Niveaus über `loa1` zählt ein Nachweis ohnehin nur 30 Minuten
([04-orchestrierung.md](../04-orchestrierung.md) Abschnitt 4).

**Kein Geheimnis je Prozess.** Das Token-Geheimnis entstand bei jedem Start neu. Ein Neustart machte
alle Tokens ungültig, und eine zweite Instanz konnte die Tokens der ersten nicht lesen. Die Tabelle
teilen alle Instanzen. Damit fällt ein Hindernis für mehrere Instanzen weg
([07-betrieb.md](../07-betrieb.md) Abschnitt 3b).

## Erwogene Alternativen

- **Das Token behalten.** Das kostet keine Tabelle. Aber parallele Tabs verlieren weiter Nachweise,
  und das Geheimnis je Prozess bleibt. Verworfen, weil das Token keine Sicherheit bringt, die die
  `kcSessionId` nicht schon bringt.
- **Ein Datensatz je Sitzung mit optimistischer Sperre.** Alle Verfahren der Sitzung in einer Zeile
  mit Versionsnummer. Bei einem Konflikt liest man neu, führt zusammen und versucht es noch einmal.
  Das geht, braucht aber eine Schleife mit Wiederholung für einen Fall, den eine Zeile je Verfahren
  gar nicht erst entstehen lässt. Verworfen.
- **Die Nachweise aus den Kanälen der Sitzung ableiten.** Die Kanäle kennen ihre
  `durableKeycloakSessionId` schon. Aber ein Kanal lebt höchstens 30 Minuten, und die Abmeldung
  beendet ihn. Die Sitzung lebt länger. Verworfen.

## Preis

- **Eine Tabelle mehr, und sie muss aufgeräumt werden.** Vier Wege löschen Zeilen: die Abmeldung in
  Keycloak, `RetentionJob`, der Widerruf eines Verfahrens und das Löschen des Kontos. Fehlt einer,
  bleiben Nachweise übrig, die nicht mehr gelten dürfen.
- **Die Nachweise sind jetzt Zustand im Server.** Vorher hielt Keycloak sie, der Orchestrator
  vergaß sie. Jetzt liegen sie in der Datenbank des Orchestrators und gehören zu den Daten, für die
  Aufbewahrungsfristen gelten ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).
- **Die Meldung am Ende bleibt Best-Effort.** Geht `flow-end` verloren, beginnt der nächste
  Durchlauf ohne die Nachweise dieses Kanals. Das war beim Token genauso
  ([invarianten.md](../invarianten.md) I-23).

## Was mit der Umsetzung entfällt

- Im Orchestrator: `RestoreDataCodec`, die Klassen `RestoreData` und `RestoreDataResponse`, der
  Endpunkt `GET .../restore-data`, das Feld `restoreData` im `PATCH` (`kcSessionId` bleibt) und die
  Warnung zum Token-Geheimnis in `DeploymentTopology`.
- In der Extension: die Notizen `orchestrator_restore_data` und `RESTORE_SUBMITTED`.
