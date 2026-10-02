> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `WEB_SELECT_METHOD`

Mit diesem Intent beginnt im `WEB`-Kanal jede Anmeldung und jeder Step-up, wenn nichts anderes
angegeben ist ([05-api.md](../05-api.md) Abschnitt 3, ADR-8 in
[12-entscheidungen.md](../12-entscheidungen.md)). Der zweite mögliche Einstieg im Web-Kanal ist
[`REGISTER`](register.md).

Die Journey hat nur einen einzigen Zustand. Er bietet alle im Web-Kanal nutzbaren Tools, die
hier etwas nachweisen können, in einem gemeinsamen `selectMethod`-Schritt an. Es gibt keine Kette
von Ausweichwegen, keine Identifizierung und kein Angebot, ein Verfahren einzurichten. Ob überhaupt
ein Niveau angefragt wird und welches, entscheidet Keycloak selbst: Seine Ablaufkonfiguration
(Conditional-LoA-Subflows) legt es fest. Die Journey prüft nur, ob die Nachweise dieses Niveau schon
erreichen (siehe unten).

```mermaid
stateDiagram-v2
  [*] --> SelectMethod
  SelectMethod --> SelectMethod: ein Tool abgelehnt, weitere übrig
  SelectMethod --> SelectMethod: Nachweis erbracht, Niveau noch nicht erreicht - Angebot neu aufgebaut
  SelectMethod --> [*]: alle abgelehnt -> Cancel
  SelectMethod --> [*]: Einmalkennwort unter dem verlangten Niveau -> Abort
  SelectMethod --> Finished: Nachweis erbracht, Niveau erreicht (Konto oder Einladung)
```

Derselbe Zustand bedient zwei Fälle im Web-Kanal. Sie unterscheiden sich nur im Feld
`accountAlreadyKnown`:

- **Erste Anmeldung** (`ctx.account` ist `null`): Angeboten werden alle Tools, die ihr Subjekt aus der
  Eingabe selbst finden (`CandidateTools.forLookupLogin`), nie eine Identifizierung. Das sind die
  Anmeldungen über die E-Mail-Adresse, die ein Konto finden, und `auth-invite-lookup`, das mit
  KVNR oder Partnernummer und Einmalkennwort eine Einladung findet
  ([ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)). Nach einem Einmalkennwort ist
  das Subjekt des Kanals die Einladung, kein Konto; die Journey ist dann sofort fertig, denn weitere
  Nachweise kann eine Einladung nicht sammeln. Verlangt die Anmeldung ein höheres Niveau, als die
  Einladung trägt, bricht sie ab, bevor etwas gebunden wird. Diesen Fall gibt es nur, wenn der Schalter `loa1` auf den Orchestrator stellt
  ([ADR-42](../adr/ADR-042-loa1-anmeldung-umschalten.md)); sonst meldet Keycloaks Passwortformular
  das Konto schon vorher.
- **Step-up** (das Konto ist schon vor Beginn der Journey auf dem Kanal gesetzt): Angeboten werden nur
  Anmelde-Tools für dieses Konto.

Bei jedem Ereignis (`Started`, `EvidenceReported`, `ActionCompleted`) prüft die Journey erneut, ob
das Vorhandene reicht, und baut die Kandidatenliste ganz neu auf. Ein Nachweis kann nämlich schon
vorliegen, bevor überhaupt etwas angeboten wurde, etwa aus einem eigenen Verfahren von Keycloak oder
aus RestoreData.
