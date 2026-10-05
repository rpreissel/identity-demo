# Verfahren `fsc`

**Was es ist:** Der Nutzer weist nach, wer er ist, indem er seine Personendaten und einen
Freischaltcode eingibt. Die Personendaten sind KVNR (oder Partnernummer), Name, Vornamen und
Geburtsdatum. Den Freischaltcode hat ihm das Personenverzeichnis ausgestellt.

**Wozu es dient:** Es ist ein Verfahren zur **Identifizierung**, also zur Feststellung, wer jemand
wirklich ist. Anders als ein Anmeldeverfahren wird es nicht im Konto eingerichtet. Es liefert
höchstens das Niveau `loa2`.

Das **Personenverzeichnis** enthält die Stammdaten der Versicherung: die Personen mit Partnernummer,
Name und Geburtsdatum. Die **KVNR** ist die Krankenversichertennummer. Bei der Prüfung stellt das
Tool fest, um welche Person es sich handelt. Genau das ist die fachliche Leistung des Moduls.

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat ein einziges Tool, `ident-fsc`. Seine Rolle ist die Identifizierung
(`IDENTIFICATION`). Außer den üblichen Identitätsangaben liefert es die Claims `PERSON_ID`, `KVNR`
und `MEMBER_NUMBER`. Ein **Claim** ist eine Angabe über den Kontoinhaber, die im Konto gespeichert
wird.

| toolId | Rolle | Fassungen |
|---|---|---|
| `ident-fsc` | `IDENTIFICATION`, Claims zusätzlich `PERSON_ID`, `KVNR`, `MEMBER_NUMBER` | 1 |

Das Verfahren erbringt den Faktortyp Besitz (`{possession}`) und liefert höchstens das Niveau
`loa2`. Deklariert ist es in `tools/ident_fsc/FscToolModule.kt`.

Eine Identifizierung steht im Änderungsprotokoll (`IDENTIFIED`), nicht in `account.auth_method`,
der Liste der eingerichteten Anmeldeverfahren. Deshalb erscheint `fsc` nie in `activeMethods`
([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1).

## Ablauf

Das Modul `ident_fsc` prüft `kvnr`, `familyName`, `givenNames`, `birthDate` und `fsc` gegen das
Personenverzeichnis. Name und Geburtsdatum prüft es gegen die dort geführte Person, den Code gegen
die dort ausgestellten Freischaltcodes.

Ein Partner ohne KVNR gibt statt `kvnr` seine Partnernummer `partnerNumber` an (ADR-34). Der Client
fragt zuerst nach der KVNR. Kommen beide Nummern, zählt die KVNR, und das Tool merkt sich immer nur
eine von beiden. `kvnr` in `missingFields` steht deshalb für „KVNR oder Partnernummer“.

Das Modul `account` kennt `ident_fsc` nicht. Die Verbindung stellt erst der Orchestrator her, also
der Server dieses Projekts, wenn er `Completed.Identified` verarbeitet
([Orchestrierung](../04-orchestrierung.md)).

Für die Richtigkeit der Daten ist das Personenverzeichnis verantwortlich. Das Tool ist nur der Weg
dorthin (`ClaimSource.PERSON_DIRECTORY`). Der Freischaltcode belegt das Verfahren. Den Code prüft
`ident_fsc` über den Port `ActivationCodes` (`digest`, `isValid`). Ein **Port** ist eine fest
vereinbarte Schnittstelle zu einem anderen System. `Freischaltcodes` implementiert diesen Port
(ADR-31, Nachtrag). Die Person findet `ident_fsc` über `PersonDirectory`
([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 7).

## Aufrufe und Abweichungen vom allgemeinen Muster

Die Aufrufe zeigt [05-api.md](../05-api.md) Abschnitt 2 („Ein Tool-Durchlauf als Beispiel“,
Schritte 1 bis 3). Davon weicht `ident-fsc` so ab:

- Die fehlenden Felder (`missingFields`) kommen gestaffelt in einem einzigen Schritt `input`:
  zuerst `kvnr`/`familyName`/`givenNames`/`birthDate`, danach `fsc`.
- Das Tool prüft die Personendaten, sobald sie vollständig sind. Erst wenn sie zum
  Personenverzeichnis passen, fragt es nach dem Freischaltcode.
- Abgelehnte Personendaten werden verworfen. Danach fehlen wieder alle vier Felder. Bei einem
  abgelehnten Code wird nur der Code verworfen.
- Beide Ablehnungen zählen als Fehlversuch. Die Sperre für die Person (`isIdentLockedOut`) gilt
  aber nur für den Code. Denn nur er ist ein Geheimnis, das man erraten könnte.
- Werden die Personendaten abgelehnt, erfährt man nie, welches Feld nicht passte oder ob es die
  KVNR gibt.
- Wie viele Bildschirme ein Client daraus macht, entscheidet er selbst ([Frontend](../10-frontend.md)).
  App und Keycloak zeigen zuerst die Personendaten, dann den Code. Nach einem Fehlversuch bleiben
  sie auf der Seite, von der aus abgeschickt wurde.
- `GET` baut `stepData` bei jedem Aufruf neu aus den Daten des Moduls auf. Ist das Tool schon
  abgeschlossen, zeigt die Antwort bereits auf das nächste Tool. So lässt sich ein unterbrochener
  Ablauf fortsetzen.

## In der Demo

Das Personenverzeichnis ist ein Stellvertreter (`/mock-personenverzeichnis`). Die Auswahl
„Testperson übernehmen“ füllt Personendaten und Freischaltcode vor (`demo.persons`,
[05-api.md](../05-api.md) Abschnitt 1).
