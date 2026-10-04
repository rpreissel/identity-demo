# Verfahren `kvnr`

Eine bestätigte Identität der Person im Personenverzeichnis zuordnen, über die
Versichertennummer (KVNR) oder die Partnernummer. `ident-kvnr` ist der zweite Schritt nach einer
Identifizierung, die nur bezeugt, was sie liest: nach `ident-eid` ([Verfahren `eid`](eid.md)) und
`ident-nect` ([Verfahren `nect`](nect.md)). ADR-18 teilt die Identifizierung so in zwei Akte:
bestätigen und zuordnen.

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `ident-kvnr` | `CORRELATION`, Claims `PERSON_ID`, `KVNR`, `MEMBER_NUMBER`, `requires` die bestätigten Identitätsattribute `FAMILY_NAME`, `GIVEN_NAMES`, `BIRTH_DATE` | 1 |

Faktoren `{}`, Niveau höchstens `loa2`. Deklariert in `tools/ident_kvnr/KvnrToolModule.kt`.

## Ablauf

`ident-kvnr` ist ein eigenes Tool mit nur einem Schritt (`input`, Feld `kvnr` – oder ohne KVNR
`partnerNumber`, ADR-34). Es löst die Versichertennummer über
`PersonDirectory.findPersonIdByKvnr` auf, die Partnernummer über `findPersonIdByPartnerNumber`; kommen
beide, zählt die KVNR. Das geschieht im Controller, nicht im Handler, denn `ident_kvnr` darf
`personenverzeichnis` nicht direkt kennen ([Projektrahmen](../08-projektrahmen.md) Abschnitt 3).
Danach behauptet das Tool unter `PERSON_DIRECTORY` die `PERSON_ID`, bei angegebener KVNR auch die
`KVNR` und bei Versicherten die `MEMBER_NUMBER` (ADR-34).

Es hat die Rolle `CORRELATION` (ADR-18,
[03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 1). Das sagt ausdrücklich, dass eine
eingetippte Nummer für sich nichts beweist; `factorTypes={}` folgt daraus, definiert es aber nicht.
Sicher wird der Schritt durch zwei Dinge:
`requires` (die bestätigten Identitätsattribute müssen im Konto vorliegen, sonst lässt sich das
Tool gar nicht starten) und `IdentityResolver.attestedIdentityMatches`. Das prüft, bevor der Anker
geschrieben wird, ob die Stammdaten hinter der Nummer zur bestätigten Identität passen.

Gehört die Nummer zu einem Konto, das es schon gibt, ist das kein Fehler des Nutzers, sondern eine
Folge der Reihenfolge: Die Bestätigung brauchte ein Konto, bevor die Zuordnung laufen konnte. Das
verwerfbare Konto geht dann im gefundenen auf, samt Bestätigung, Ankern und Protokoll der
Identifizierung ([12-entscheidungen.md](../12-entscheidungen.md) ADR-20). Danach steht die
Registrierung da, wo jeder andere Weg zu einem bestehenden Konto auch stünde: bei der Frage, ob
dieses Gerät mit einem anderen Konto verknüpft ist, und beim Angebot, ein vorhandenes Verfahren
nachzuweisen, statt ein neues einzurichten.

Zwischen beiden Schritten steht keine Ja/Nein-Frage: Nach der Bestätigung zeigt `next` direkt
auf `ident-kvnr` (`RegisterState.Assigning`). Wer die Nummer nicht angeben will, bricht den Schritt
ab (`DELETE /tools/api/ident-kvnr/v1/{toolSessionId}`, im Frontend „Jetzt nicht"). Der
Durchlauf geht dann normal weiter, und das Konto bleibt Interessent
([Orchestrierung](../04-orchestrierung.md), ADR-10): mit vollständig bestätigter Identität, nur ohne
Zuordnung zum Personenverzeichnis. Es ist ein Ausweichzustand, kein Pflichtzustand.

Im Änderungsprotokoll hinterlässt der Durchlauf neben der Bestätigung eine eigene Zeile mit
`role=CORRELATION`, denn in diesem Moment entsteht der `PERSON_ID`-Anker
([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1).

## Fehlerfälle

Zusätzlich zum allgemeinen Vertrag:

- unbekannte Versichertennummer -> `Failed("Versichertennummer konnte nicht zugeordnet werden")`;
- unbekannte Partnernummer -> `Failed("Partnernummer konnte nicht zugeordnet werden")`.

Bei beiden Nummern kommt bewusst dieselbe Antwort, egal ob die Nummer gar nicht existiert oder zu
jemand anderem gehört; sonst ließe sich daraus ablesen, ob es eine Nummer gibt. Passt die Nummer zu
einer anderen Person als der bestätigten, ist das ein Konflikt (`409`), kein Fehlschlag des Tools.
