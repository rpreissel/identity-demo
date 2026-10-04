> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `DELETE_ACCOUNT`

Mit dieser Journey löscht ein Nutzer sein eigenes Konto. Die Bestätigung kommt immer zuerst und
wird nie hinter einem Step-up versteckt.

```mermaid
stateDiagram-v2
  [*] --> ConfirmPending
  ConfirmPending --> [*]: abgelehnt -> Cancel
  ConfirmPending --> ConfirmationRequired: Niveau erreicht, letzter Nachweis älter als die Frist
  ConfirmPending --> Finished: Niveau erreicht, letzter Nachweis jung genug -> Konto gelöscht, Abmeldung
  ConfirmPending --> STEP_UP: gefordertes Niveau noch nicht erreicht
  STEP_UP --> ConfirmPending: SubJourneyFinished mit ausreichendem Niveau -> sofort Perform(DeleteAccount)
  STEP_UP --> [*]: SubJourneyFinished unter dem geforderten Niveau -> Cancel
  STEP_UP --> [*]: SubJourneyCancelled -> Cancel
  ConfirmationRequired --> ConfirmationRequired: ein Tool abgelehnt, weitere übrig
  ConfirmationRequired --> [*]: alle abgelehnt -> Cancel
  ConfirmationRequired --> Finished: Nachweis erbracht -> Konto gelöscht, Abmeldung
  Finished --> [*]
```

`ConfirmPending` ist ein `AnswerableState`; seine Frage ist als folgenschwer markiert
(`destructive: true`). Nach der Zustimmung gilt dieselbe Schwelle wie bei
[`MANAGE_AUTH_METHODS`](manage-auth-methods.md): `Action.DeleteAccount.requiredAcr` ruft dieselbe
Funktion `selfServiceAcrFloor` auf.

Gelöscht wird nur mit einem frischen Nachweis: Der jüngste Nachweis der Sitzung darf höchstens
fünf Minuten alt sein (`AuthPolicy.hasFreshProof`, `identity.policy.self-service-max-age`). Ist er
älter, weist der Nutzer noch einmal ein Verfahren nach (ein beliebiges aktives, auf beliebigem
Niveau). So wird ein Konto nie aus einer länger offenen Sitzung heraus gelöscht. Musste vorher ein
Step-up laufen, zählt dessen Nachweis bereits.

Der letzte Übergang ist `Transition.Perform(Action.DeleteAccount, resumeState = ConfirmPending)`.
Wird die Journey danach mit `ActionCompleted` fortgesetzt, wird daraus `Transition.Logout`: Das Konto
wird gelöscht und der Kanal beendet. Unmittelbar vor der Ausführung prüft `JourneyActionExecutor`
`requiredAcr(account)` noch einmal, so wie vor `Action.RevokeAuthMethod` geprüft wird, ob sich der
Nutzer aussperren würde.

Der Nachweis in `ConfirmationRequired` führt direkt zu `Action.DeleteAccount` und nie über
`Action.AcceptProof`. Er erlaubt genau diese eine Löschung und wird nie zu einem dauerhaften
Nachweis der Sitzung (`MethodEvidence`, Orchestrierung, Abschnitt 5).
