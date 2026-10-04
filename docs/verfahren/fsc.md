# Verfahren `fsc`

Identifizieren mit Personendaten und Freischaltcode: Die Person gibt KVNR (oder Partnernummer),
Name, Vornamen und Geburtsdatum an, dazu den Freischaltcode, den ihr das Personenverzeichnis
ausgestellt hat. Dabei stellt das Tool fest, um welche Person es sich handelt – genau das ist die
fachliche Leistung des Moduls.

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `ident-fsc` | `IDENTIFICATION`, Claims zusätzlich `PERSON_ID`, `KVNR`, `MEMBER_NUMBER` | 1 |

Faktor `{possession}`, höchstens `loa2`. Deklariert in `tools/ident_fsc/FscToolModule.kt`. Eine
Identifizierung steht im Änderungsprotokoll (`IDENTIFIED`), nicht in `account.auth_method`; `fsc`
erscheint deshalb nie in `activeMethods` ([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1).

## Ablauf

`ident_fsc` prüft `kvnr`, `familyName`, `givenNames`, `birthDate` und `fsc` gegen das Personenverzeichnis:
Name und Geburtsdatum gegen die dort geführte Person, den Code gegen die dort ausgestellten
Freischaltcodes. Ein Partner ohne KVNR gibt statt `kvnr` seine Partnernummer `partnerNumber` an
(ADR-34). Der Client fragt zuerst nach der KVNR; kommen beide Nummern, zählt die KVNR, und das Tool
merkt sich immer nur eine von beiden. `kvnr` in `missingFields` steht deshalb für „KVNR oder
Partnernummer“. Das Modul `account` kennt `ident_fsc` nicht; die Verbindung stellt erst
der Orchestrator her, wenn er `Completed.Identified` verarbeitet
([Orchestrierung](../04-orchestrierung.md)).

Das Personenverzeichnis steht für die Daten ein, das Tool ist nur der Weg dorthin
(`ClaimSource.PERSON_DIRECTORY`); der Freischaltcode belegt das Verfahren. Den Code prüft
`ident_fsc` über den Port `ActivationCodes` (`digest`, `isValid`), den `Freischaltcodes`
implementiert (ADR-31, Nachtrag); die Person findet es über `PersonDirectory`
([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 7).

## Aufrufe und Abweichungen vom allgemeinen Muster

Die Aufrufe zeigt [05-api.md](../05-api.md) Abschnitt 2 („Ein Tool-Durchlauf als Beispiel“,
Schritte 1 bis 3). Was dabei vom allgemeinen Muster abweicht:

- Die `missingFields` kommen gestaffelt in einem einzigen Schritt `input`: zuerst
  `kvnr`/`familyName`/`givenNames`/`birthDate`, danach `fsc`.
- Die Personendaten werden geprüft, sobald sie vollständig sind. Erst wenn sie zum
  Personenverzeichnis passen, fragt das Tool nach dem Freischaltcode.
- Abgelehnte Personendaten werden verworfen; danach fehlen wieder alle vier. Bei einem abgelehnten
  Code wird nur der Code verworfen.
- Beide Ablehnungen zählen als Fehlversuch. Die Sperre für die Person (`isIdentLockedOut`) greift
  beim Code, denn nur er ist ein Geheimnis, das man erraten könnte.
- Wird die Eingabe der Personendaten abgelehnt, erfährt man nie, welches Feld nicht passte oder
  ob es die KVNR gibt.
- Wie viele Bildschirme ein Client daraus macht, entscheidet er selbst ([Frontend](../10-frontend.md)).
  App und Keycloak zeigen zuerst die Personendaten, dann den Code, und bleiben nach einem
  Fehlversuch auf der Seite, von der aus abgeschickt wurde.
- `GET` baut `stepData` bei jedem Aufruf neu aus den Daten des Moduls auf. Ist das Tool schon
  abgeschlossen, zeigt die Antwort bereits auf das nächste Tool (Fortsetzen nach Unterbrechung).

## In der Demo

Das Personenverzeichnis ist ein Stellvertreter (`/mock-personenverzeichnis`). Die Auswahl
„Testperson übernehmen“ füllt Personendaten und Freischaltcode vor (`demo.persons`,
[05-api.md](../05-api.md) Abschnitt 1).
