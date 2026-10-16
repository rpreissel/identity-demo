# ADR-61: Niveau und frischer Nachweis sind eine gemeinsame Sub-Journey

**Status:** entschieden und umgesetzt 2026-10-13 (Issue `DPoP-demo-5966`). Folgt dem Muster von
`RE_IDENTIFY` und `STEP_UP` als Sub-Journey (ADR-6, [04-orchestrierung.md](../04-orchestrierung.md)
Abschnitt 7).

**Worum es geht.** Drei Intents wirken auf das Konto eines angemeldeten Kanals: `DELETE_ACCOUNT`,
`MANAGE_AUTH_METHODS` und `CONFIRM_PEER_LOGIN`. Alle drei verlangten vorher dasselbe: Die Sitzung
muss ein Niveau erreichen, sonst läuft ein Step-up; und ihr jüngster Nachweis muss frisch sein,
sonst weist der Nutzer ein aktives Verfahren noch einmal nach. Jede der drei Strategien hatte dafür
einen eigenen Zustand `ConfirmationRequired`, eine eigene Prüfung des Niveaus, eine eigene
Behandlung des zurückkehrenden Step-ups und eigene Tests. Die Regel „vor einer heiklen Aktion ein
frischer Nachweis“ stand dreimal im Code, mit drei Gelegenheiten, voneinander abzuweichen.

**Entscheidung.** Die Prüfung ist eine eigene Sub-Journey, `FRESH_PROOF`
([Katalog](../journeys/fresh-proof.md)):

- Ein Intent fordert sie per `Transition.RequireSubJourney` an und gibt dabei das verlangte Niveau
  und seinen Zweck mit (`FreshProofState.forSubJourney`). Der
  Zweck (`Purpose`) bestimmt die Wortwahl der Auswahlseite und, ob ein Step-up darin die erneute
  Identifizierung anbieten darf.
- Die Sub-Journey prüft erst das Niveau (sonst `STEP_UP` als Sub-Journey der Sub-Journey), dann die
  Frische (sonst `Reproving`, ein beliebiges aktives Verfahren). Ein Step-up, der das Niveau
  erreicht, ist zugleich der frische Nachweis. Ein Step-up unter dem Niveau oder eine Ablehnung
  beendet sie mit `Cancel`; ein schwächerer Nachweis springt nie ein.
- Der Nachweis in `Reproving` wird nie als `MethodEvidence` gespeichert und schreibt nichts ins
  Anmeldeprotokoll; er erlaubt genau die eine Aktion des Aufrufers.
- Die drei Intents behalten nur ihren Zweck: `DELETE_ACCOUNT` fragt und löscht,
  `MANAGE_AUTH_METHODS` hält den Wunsch und führt ihn aus, `CONFIRM_PEER_LOGIN` bietet `approve-qr`
  an. Auf `SubJourneyFinished(FRESH_PROOF)` führen sie aus, auf `SubJourneyCancelled` enden sie.
  Eine andere Sub-Journey, die ihr Ende meldet, gilt nie als der Nachweis.
- `FRESH_PROOF` ist nie ein Einstieg und nie direkt zu starten, wie `RE_IDENTIFY`.

**Warum.**

- **Eine Regel, eine Stelle.** Was ein frischer Nachweis ist und wann ein Step-up ihn ersetzt, steht
  einmal in `FreshProofJourney` und wird einmal getestet. Ein neuer Intent, der auf das Konto
  wirkt, bekommt die Regel mit einer Zeile.
- **Dieselbe Mechanik wie `RE_IDENTIFY`.** Sub-Journeys mit `parentJourneyId`, `SUSPENDED` und
  `SubJourneyFinished` gibt es schon; der Journey-Trace zeigt sie als eigene Journey.
- **Die HTTP-Antworten bleiben gleich.** Die Auswahlseite (`next.context = "auth"`,
  `step = "selectMethod"`) und die Texte je Zweck sind dieselben wie vorher.

**Erwogene Alternativen.**

- **Eine gemeinsame Hilfsfunktion statt einer Sub-Journey.** Sie hätte die Prüfung gebündelt, aber
  jeder Intent hätte weiter seinen Zustand `ConfirmationRequired` und die Rückkehr aus dem Step-up
  behalten müssen. Verworfen: Der Zustand ist der Kern der Dopplung, nicht die Prüfung.
- **Die erste Rückfrage von `DELETE_ACCOUNT` (`ConfirmPending`) ebenfalls streichen**, wie beim
  Logout (ADR-60). Zurückgestellt: Löschen ist nicht umkehrbar; eine Rückfrage im Vertrag schützt
  auch einen Client, der das Nachfragen vergisst.

**Preis.**

- Je Aktion am Konto entsteht eine Journey mehr, im Journey-Trace und in der Tabelle
  `auth_journey`. Beides läuft in der Aufbewahrungsfrist der Journeys mit.
- `MANAGE_AUTH_METHODS`, Wunsch „Hinzufügen“: Gibt es nichts mehr einzurichten, endet die Journey
  jetzt ohne jede Prüfung. Vorher prüfte sie das Niveau trotzdem. Der Unterschied ist nur für Konten
  sichtbar, die jedes Verfahren schon haben.
- Gespeicherte Journeys in einem Zustand `ConfirmationRequired` aus der Zeit davor lassen sich nicht
  mehr lesen. Sie leben nur Minuten; ein Deployment dazwischen beendet sie, wie die Aufbewahrung
  (`AuthJourney.expiresAt`).
