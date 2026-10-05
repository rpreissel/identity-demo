# ADR-27: Gemeinsame Typen liegen im Paket `orchestrator.kernel`

**Status:** umgesetzt; das Paket heißt heute `orchestrator.domain` und ist mit
[ADR-40](ADR-040-fachkern-im-paket-domain.md) zum fachlichen Kern geworden.

**Entscheidung.** Manche Typen werden von mehreren Paketen des Orchestrators gebraucht. Diese Typen
liegen im Paket `orchestrator.kernel`. Dieses Paket hängt selbst von keinem anderen Paket innerhalb
des Orchestrators ab. Ein ArchUnit-Test prüft, dass zwischen den Paketen des Orchestrators keine
Zyklen entstehen. Ein Zyklus bedeutet: Paket A benutzt Paket B, und B benutzt wieder A.

> **Nachtrag 2026-09-26:** Das Paket heißt jetzt `orchestrator.domain` und ist zum fachlichen Kern
> gewachsen. Dort liegen nun auch die Zustände der Journeys, `IntentStrategy`, `AuthPolicy` und
> `SessionEvidence`. Zur Regel „keine Abhängigkeit innerhalb des Orchestrators“ kommt die Regel
> „kein Framework“ hinzu ([ADR-40](ADR-040-fachkern-im-paket-domain.md)).

## Vorher

Der Backend-Code ist in Module aufgeteilt. Spring Modulith, die eingesetzte Bibliothek dafür, prüft
die Grenzen zwischen diesen Modulen. In ein Modul hinein schaut es aber nicht. Der Orchestrator ist
das größte Modul. Innerhalb davon gab es fünf Zyklen zwischen Paketen:

- `session` ↔ `policy`
- `session` ↔ `journey`
- `session` ↔ `journeytrace`
- `keycloak` ↔ `dpop`
- `session` → `api.v1`

Solche Zyklen machen den Code schwer verständlich und schwer änderbar, weil man kein Paket für sich
allein betrachten kann.

## Warum die Zyklen entstanden

In fast allen Fällen lag ein Typ im falschen Paket:

| Typ | lag in | ist aber |
|---|---|---|
| `AuthIntent` | `journey` | ein Begriff, den der ganze Orchestrator benutzt |
| `AmrSource` | `session` | eine Aussage darüber, woher ein Nachweis stammt. Das ist eine Frage der Richtlinie, nicht der Speicherung. |
| `AcrLevels` | `session` | dasselbe |
| `OrchestratorException` | `api.v1` | der Fehlertyp aller Schichten, nicht nur der Web-Schicht |

Diese Typen liegen jetzt in `kernel`. Damit sind vier der fünf Zyklen verschwunden, ohne dass sich
das Verhalten ändert.

## Zwei Fälle brauchten mehr als einen Umzug

- **`journeytrace`** schreibt ein Protokoll über Kanäle und Journeys. Bisher nahm es dafür die
  Entitäten `ChannelSession` und `AuthJourney` entgegen. Für den Aufrufer war das bequem. Es machte
  das Protokoll aber von genau den Klassen abhängig, die es protokolliert. Jetzt nimmt es zwei
  kleine Wertklassen entgegen (`LoggedChannel`, `LoggedJourney`). Damit gibt der Aufrufer
  ausdrücklich an, welche Felder protokolliert werden. Diese Angabe ist zugleich die vollständige
  Liste dessen, was in die Tabelle geschrieben wird.
- **Das Aufräumen nach Ablauf der Fristen** löscht Sessions *und* Journeys. Es gehört deshalb über
  beide Pakete und nicht in eines von ihnen hinein. Dafür gibt es das neue Paket
  `orchestrator.retention`.

## Alternative: Modulith-Substrukturen

Man hätte `@ApplicationModule` auch auf die Unterpakete setzen können. Dann hätte `modules.verify()`
die Zyklen mitgeprüft. Dagegen spricht: Die Unterpakete wären damit zu Modulen erklärt worden, die
nach außen sichtbar sind. Das sind sie aber nicht. Eine ArchUnit-Regel prüft dasselbe, ohne dieses
Versprechen abzugeben.

## Kosten

Es gibt ein Paket mehr und eine Regel, die bei jedem neuen Zyklus fehlschlägt.

Mehr dazu in [08-projektrahmen.md](../08-projektrahmen.md), Abschnitte 3 und 7.
