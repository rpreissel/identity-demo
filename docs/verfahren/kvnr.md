# Verfahren `kvnr`

**Was es ist:** Nach einer Identifizierung mit dem Online-Ausweis oder über Nect gibt der Nutzer
seine Versichertennummer (KVNR) oder seine Partnernummer ein. Damit ordnet der Server die bestätigte
Identität einer Person im Personenverzeichnis zu, also in den Stammdaten der Versicherung.

**Wozu es dient:** Online-Ausweis und Nect bestätigen nur, wer jemand ist. Welche Person der
Versicherung das ist, wissen sie nicht. Diese Zuordnung übernimmt `ident-kvnr`. Der Schritt ist
freiwillig: Wer die Nummer nicht angibt, behält ein Konto ohne Zuordnung.

`ident-kvnr` ist der zweite Schritt nach einer Identifizierung, die nur bezeugt, was sie liest. Das
sind `ident-eid` ([Verfahren `eid`](eid.md)) und `ident-nect` ([Verfahren `nect`](nect.md)).
ADR-18 teilt die Identifizierung so in zwei Schritte: bestätigen und zuordnen.

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat ein einziges Tool, `ident-kvnr`. Seine Rolle ist die Zuordnung (`CORRELATION`).
Es liefert die **Claims** `PERSON_ID`, `KVNR` und `MEMBER_NUMBER`, also Angaben über den
Kontoinhaber, die im Konto gespeichert werden. Voraussetzung (`requires`) sind die bestätigten
Identitätsangaben Name, Vornamen und Geburtsdatum.

| toolId | Rolle | Fassungen |
|---|---|---|
| `ident-kvnr` | `CORRELATION`, Claims `PERSON_ID`, `KVNR`, `MEMBER_NUMBER`, `requires` die bestätigten Identitätsattribute `FAMILY_NAME`, `GIVEN_NAMES`, `BIRTH_DATE` | 1 |

Das Tool erbringt keinen Faktor (`{}`) und liefert höchstens das Niveau `loa2`. Deklariert ist es
in `tools/ident_kvnr/KvnrToolModule.kt`.

## Ablauf

`ident-kvnr` ist ein eigenes Tool mit nur einem Schritt (`input`). Der Nutzer gibt das Feld `kvnr`
an. Wer keine KVNR hat, gibt stattdessen `partnerNumber` an (ADR-34).

Die Versichertennummer wird über `PersonDirectory.findPersonIdByKvnr` aufgelöst, die Partnernummer
über `findPersonIdByPartnerNumber`. Kommen beide, zählt die KVNR. Das geschieht im Controller, nicht
im Handler. Denn das Modul `ident_kvnr` darf das Modul `personenverzeichnis` nicht direkt kennen
([Projektrahmen](../08-projektrahmen.md) Abschnitt 3).

Danach behauptet das Tool mit der Quelle `PERSON_DIRECTORY` die `PERSON_ID`. Ist eine KVNR
angegeben, behauptet es auch die `KVNR`. Bei Versicherten kommt die `MEMBER_NUMBER` hinzu (ADR-34).

Das Tool hat die Rolle `CORRELATION` (ADR-18, [03-tool-architektur.md](../03-tool-architektur.md)
Abschnitt 1). Die Rolle sagt ausdrücklich, dass eine eingetippte Nummer für sich nichts beweist.
`factorTypes={}` folgt daraus, definiert die Rolle aber nicht. Sicher wird der Schritt durch zwei
Dinge:

- `requires`: Die bestätigten Identitätsangaben müssen im Konto vorliegen. Sonst lässt sich das
  Tool gar nicht starten.
- `IdentityResolver.attestedIdentityMatches`: Bevor der Anker geschrieben wird, prüft diese
  Funktion, ob die Stammdaten hinter der Nummer zur bestätigten Identität passen. Ein **Anker** ist
  eine Angabe, über die sich ein Konto eindeutig wiederfinden lässt.

Es kann sein, dass die Nummer zu einem Konto gehört, das es schon gibt. Das ist kein Fehler des
Nutzers, sondern eine Folge der Reihenfolge: Die Bestätigung brauchte ein Konto, bevor die
Zuordnung laufen konnte. Die **Journey**, also der geführte Ablauf, wechselt dann zum gefundenen
Konto. Dabei übernimmt sie aus dem verwerfbaren Konto die Bestätigung, die Anker und das Protokoll
der Identifizierung ([12-entscheidungen.md](../12-entscheidungen.md) ADR-20). Verwerfbar heißt: Das
Konto ist keiner Person zugeordnet, und in ihm wurde nie ein Anmeldeverfahren eingerichtet.

Danach ist die Registrierung an derselben Stelle wie auf jedem anderen Weg zu einem bestehenden
Konto. Es folgen die Frage, ob dieses Gerät mit einem anderen Konto verknüpft ist, und das Angebot,
ein vorhandenes Verfahren nachzuweisen, statt ein neues einzurichten.

Zwischen Bestätigung und Zuordnung steht keine Ja/Nein-Frage. Nach der Bestätigung zeigt `next`
direkt auf `ident-kvnr` (`RegisterState.Assigning`). Wer die Nummer nicht angeben will, bricht den
Schritt ab (`DELETE /tools/api/ident-kvnr/v1/{toolSessionId}`, im Frontend „Jetzt nicht").

Der Durchlauf geht dann normal weiter, und das Konto bleibt ein **Interessent**
([Orchestrierung](../04-orchestrierung.md), ADR-10). Ein Interessent ist ein Konto, das keiner
Person im Personenverzeichnis zugeordnet ist. Hier hat es eine vollständig bestätigte Identität,
nur eben ohne Zuordnung. Dieser Zustand ist ein Ausweichzustand, kein Pflichtzustand (siehe
[Glossar](../glossar/glossar.md)).

Im Änderungsprotokoll hinterlässt der Durchlauf neben der Bestätigung eine eigene Zeile mit
`role=CORRELATION`. Denn in diesem Moment entsteht der `PERSON_ID`-Anker
([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1).

## Fehlerfälle

Zusätzlich zum allgemeinen Vertrag gilt:

- unbekannte Versichertennummer -> `Failed("Versichertennummer konnte nicht zugeordnet werden")`;
- unbekannte Partnernummer -> `Failed("Partnernummer konnte nicht zugeordnet werden")`.

Bei beiden Nummern kommt bewusst dieselbe Antwort, egal ob die Nummer gar nicht existiert oder zu
jemand anderem gehört. Sonst ließe sich daraus ablesen, ob es eine Nummer gibt. Passt die Nummer zu
einer anderen Person als der bestätigten, ist das ein Konflikt (`409`), kein Fehlschlag des Tools.
