> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `DELETE_ACCOUNT`

Mit dieser Journey löscht ein Nutzer sein eigenes Konto. Sie setzt einen Kanal voraus, der schon
`AUTHENTICATED` ist, also eine angemeldete Verbindung von App oder Website.

Zuerst kommt in jedem Fall die Ja/Nein-Frage, ob der Nutzer sein Konto wirklich löschen will. Sie
wird nie erst nach einem Step-up gestellt, also nach dem Anheben des Sicherheitsniveaus. Wie die
Frage technisch aussieht, beschreibt [05-api.md](../05-api.md) im Abschnitt „Das
`Prompt`-Objekt“.

```mermaid
stateDiagram-v2
  [*] --> ConfirmPending
  ConfirmPending --> [*]: abgelehnt -> Cancel
  ConfirmPending --> FRESH_PROOF: zugestimmt -> Niveau und frischer Nachweis
  FRESH_PROOF --> Finished: SubJourneyFinished -> Konto gelöscht, Abmeldung
  FRESH_PROOF --> [*]: SubJourneyCancelled -> Cancel
  Finished --> [*]
```

**Welches Niveau verlangt wird.** `ConfirmPending` ist ein `AnswerableState`, also ein Zustand, der
eine Ja/Nein-Frage stellt. Seine Frage ist als folgenschwer markiert (`destructive: true`). Nach der
Zustimmung gilt dieselbe Schwelle wie bei [`MANAGE_AUTH_METHODS`](manage-auth-methods.md), denn
`Action.DeleteAccount.requiredAcr` ruft dieselbe Funktion `selfServiceAcrFloor` auf. Verlangt wird
`loa2`. Für ein Konto, das nie identifiziert wurde, reicht `loa1`.

**Nur mit frischem Nachweis.** Ein Nachweis ist das, was der Nutzer in dieser Sitzung bewiesen hat,
etwa ein richtig eingegebenes Passwort. Gelöscht wird nur mit einem frischen Nachweis: Der jüngste
Nachweis der Sitzung darf höchstens fünf Minuten alt sein. Ist er älter, weist der Nutzer noch
einmal ein beliebiges aktives Verfahren nach. So wird ein Konto nie aus einer Sitzung heraus
gelöscht, die schon länger offen ist. Niveau und Frische prüft nicht diese Journey, sondern die
gemeinsame Sub-Journey [`FRESH_PROOF`](fresh-proof.md) ([ADR-61](../adr/ADR-061-frischer-nachweis-als-gemeinsame-sub-journey.md)); sie startet erst nach
der Zustimmung.

**Wie gelöscht wird.** Der letzte Übergang ist
`Transition.Perform(Action.DeleteAccount, resumeState = ConfirmPending)`. Die Journey entscheidet
damit nur, dass gelöscht werden soll. Ausgeführt wird die Löschung danach vom gemeinsamen
Mechanismus. Wird der Durchlauf anschließend mit `ActionCompleted` fortgesetzt, wird daraus
`Transition.Logout`: Das Konto ist gelöscht, und der Kanal wird beendet.

Unmittelbar vor der Ausführung prüft `JourneyActionExecutor` `requiredAcr(account)` noch einmal.
Das entspricht der Prüfung vor `Action.RevokeAuthMethod`, ob sich der Nutzer aussperren würde.

Der Nachweis aus `FRESH_PROOF` erlaubt genau diese eine Löschung. Er wird nie als dauerhafter
Nachweis der Sitzung gespeichert (`MethodEvidence`, siehe Orchestrierung, Abschnitt 8). Meldet
eine andere Sub-Journey ihr Ende, löscht die Journey nicht, sondern endet mit `Cancel`.
