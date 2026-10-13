> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `FRESH_PROOF`

Mit dieser Sub-Journey prüft ein Intent, bevor er auf das Konto wirkt, zwei Dinge: Die Sitzung hat
das verlangte Sicherheitsniveau, und ihr jüngster Nachweis ist frisch. Nie direkt, nur über
`Transition.RequireSubJourney`; genutzt von [`DELETE_ACCOUNT`](delete-account.md),
[`MANAGE_AUTH_METHODS`](manage-auth-methods.md) und [`CONFIRM_PEER_LOGIN`](confirm-peer-login.md)
([ADR-61](../adr/ADR-061-frischer-nachweis-als-gemeinsame-sub-journey.md)).

```mermaid
stateDiagram-v2
  [*] --> Start
  Start --> STEP_UP: Niveau noch nicht erreicht
  STEP_UP --> Finished: SubJourneyFinished, Niveau erreicht - der Step-up ist der frische Nachweis
  STEP_UP --> [*]: SubJourneyFinished unter dem Niveau, oder SubJourneyCancelled -> Cancel
  Start --> Finished: Niveau erreicht, jüngster Nachweis jung genug
  Start --> Reproving: Niveau erreicht, jüngster Nachweis älter als die Frist
  Start --> [*]: kein aktives Verfahren nutzbar -> Abort
  Reproving --> Reproving: ein Tool abgelehnt, weitere übrig
  Reproving --> [*]: alle abgelehnt -> Cancel
  Reproving --> Finished: Nachweis erbracht
  Finished --> [*]
```

**Was der Aufrufer vorgibt.** `FreshProofState.forSubJourney(requiredAcr, purpose)`: das
verlangte Niveau und den Zweck (`Purpose`). Der Zweck
bestimmt die Wortwahl der Auswahlseite und, ob ein Step-up die erneute Identifizierung als Ausweg
anbieten darf. Für `CONFIRM_PEER_LOGIN` darf er das nicht: Wer einen fremden Login bestätigt, soll
sich dafür keine frische Identität verschaffen können. Was der Nachweis danach erlaubt, entscheidet
allein der Aufrufer.

**Niveau zuerst.** Reicht das Niveau nicht, läuft [`STEP_UP`](step-up.md) als Sub-Journey dieser
Sub-Journey. Erreicht er das Niveau, ist er zugleich der frische Nachweis; ein zweiter wird nicht
verlangt. Bleibt er darunter oder lehnt der Nutzer ihn ab, endet `FRESH_PROOF` mit `Cancel`. Ein
schwächerer Nachweis springt nie ein: Sonst käme eine Sitzung, die das Niveau nicht erreicht, mit
genau dem Verfahren durch, das es nicht erreichen konnte.

**Dann die Frische.** Der jüngste Nachweis der Sitzung darf höchstens fünf Minuten alt sein
(`AuthPolicy.hasFreshProof`, `identity.policy.self-service-max-age`). Ist er älter, weist der
Nutzer in `Reproving` noch einmal ein beliebiges aktives Verfahren nach, egal welches Niveau es
erreicht (`CandidateTools.forReconfirmation`). Dieser Nachweis führt direkt zum Ende der
Sub-Journey und nie über `Action.AcceptProof`: Er wird nie als Nachweis der Sitzung
(`MethodEvidence`) gespeichert und erlaubt genau die eine Aktion des Aufrufers.

**Was zurückkommt.** Der Aufrufer erhält `SubJourneyFinished(FRESH_PROOF)` und führt dann aus, was
er wollte; bei `SubJourneyCancelled` endet auch er. Ins Anmeldeprotokoll schreibt `FRESH_PROOF`
nichts, nur ein Step-up darin schreibt seinen eigenen Eintrag.
