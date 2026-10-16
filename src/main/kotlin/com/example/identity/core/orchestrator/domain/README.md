# Der Fachkern des Orchestrators: Lesereihenfolge

Dieses Paket enthält die fachlichen Regeln des Orchestrators, frei von Spring, JPA und HTTP
(ADR-40). Es entscheidet, es handelt nicht: Jede Journey liest einen Kontext und nennt, was als
Nächstes geschehen soll. Ausgeführt wird das außerhalb, in `orchestrator.journey`.

Wer das Paket zum ersten Mal liest, braucht sechs Begriffe. In dieser Reihenfolge erschließen sie
sich am leichtesten:

1. **`journey/Journey.kt`** – Was eine Journey ist: ein Objekt je Ziel („anmelden“, „registrieren“,
   „Verfahren verwalten“), mit seinen Startpunkten und einer Funktion `transition(state, event, ctx)`.
   `AccountJourney` ist die Variante, die nur angemeldet läuft und darum einen Kontext mit Konto liest.
2. **`journey/state/JourneyState.kt`** – Wo auf dem Weg ein Durchlauf steht. Jede Journey hat ihre
   eigene versiegelte Zustandsmenge in `state/`. Die drei Formen: ein `OfferingState` bietet Tools an
   und merkt sich, was abgelehnt wurde (`Offer`, `DeclinePolicy`); ein `AnswerableState` wartet auf
   Ja/Nein (`Question`); ein `ToolFreeState` ist ein Entscheidungspunkt ohne Tool. `Screen` nennt die
   Seite, die ein gezeigter Zustand im Client bekommt.
3. **`journey/JourneyEvent.kt`** – Was einer Journey widerfährt: `Started`, `OfferExhausted`,
   `ActionCompleted`, `SubJourneyFinished`, `SubJourneyCancelled`, `Answered`. Der Abschluss eines
   Tools ist kein Ereignis: Der Zustand verbucht ihn selbst (`JourneyState.booking`). Jeder Zustand nennt die Ereignisse, die er erwartet; alles andere ist
   `notExpectedIn(state)`, ein Fehler, nie ein stilles Weiter.
4. **`journey/Transition.kt`** – Was die Journey als Antwort nennt: `To(state)`, `Perform(action)`,
   `RequireSubJourney`, `Authenticated`, `Cancel`, `Logout`, `Abort`. Die Nebenwirkungen selbst stehen
   in **`journey/Action.kt`**.
5. **`journey/JourneyContext.kt`** – Alles, was eine Journey sehen darf: wer sich anmeldet
   (`SigningIn`), die Nachweise der Sitzung, die Untergrenze des Kanals, die verfügbaren Tools, die
   Policy. `OpenJourneyContext` kann noch ohne Konto sein, `AccountJourneyContext` hat eines.
   `hasReached` und `canReach` sind die beiden Fragen, die eine Journey am häufigsten stellt.
6. **`journey/CandidateTools.kt`** – Welche Tools für ein Angebot infrage kommen, als Fragen an den
   Kontext: `authCandidates()`, `enrollmentCandidates()`, `identificationCandidates()` und so weiter.
   Keine Tool-Id steht hier im Klartext; alles folgt aus der Rolle im Katalog.

Danach lässt sich jede Journey lesen. Zu jeder gehören drei Dinge: das Objekt in
**`journeys/`**, seine Zustände in **`journey/state/`** und das Diagramm mit Beispielläufen in
`docs/journeys/<name>.md`. Eine gute erste Journey ist `StepUpJourney`: zwei Zustände, ein Ziel.
`FastAccessJourney` und `RegisterJourney` teilen ihren Weg zum geforderten Niveau in
`journeys/PathToRequiredLevel.kt`; das ist der Ort, an dem „eigene Verfahren zuerst, dann ein
neues, zuletzt eine Identifizierung“ einmal steht.

Für Grenzfälle gibt es zwei weitere Dateien in `journey/`:

- **`AccountRules.kt`** und **`CredentialRules.kt`**: Was beim Ausführen einer Aktion gilt, zum
  Beispiel auf welches Konto ein Nachweis geschrieben werden darf und unter welchem Niveau. Reine
  Funktionen; der Ausführer liest, fragt hier und schreibt.
- **`AbortMessages.kt`**: Die eine Stelle, an der ein strukturierter Grund der Policy zu einem
  Text für den Nutzer wird.

**`policy/`** beantwortet, was Nachweise in Kombination bedeuten: `AuthPolicy` ist die Schnittstelle,
`DefaultAuthPolicy` die Rechnung (IAL und AAL, Alter eines Nachweises, Kappung durch das
Einrichtungsniveau), `SessionEvidence` das, worüber gerechnet wird. Eine Journey kennt nur die
Schnittstelle.

**`Journeys.kt`** im Wurzelpaket zählt alle Journeys auf und prüft beim Start ihre Startpunkte.
Eine neue Journey wird dort eingetragen, sonst nirgends.

Was hier bewusst fehlt: `@Service` und `@Component`, Repositories, Uhren und Zufall, Wire-Typen.
Wer so etwas braucht, ist in `orchestrator.journey` richtig (`JourneyService`,
`JourneyActionExecutor`, `JourneyContextFactory`, `JourneyRouting`). Die Fachsprache steht in
`docs/04-orchestrierung.md`, die Invarianten in `docs/invarianten.md`.
