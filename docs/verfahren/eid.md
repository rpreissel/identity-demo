# Verfahren `eid`

**Was es ist:** Der Nutzer weist mit der Online-Ausweisfunktion seines Personalausweises nach, wer
er ist. Die Karte liefert ihre Ausweisdaten, und der Nutzer bestätigt mit seiner eID-PIN, dass er
der Besitzer ist.

**Wozu es dient:** Es ist ein Verfahren zur **Identifizierung**, also zur Feststellung, wer jemand
wirklich ist. Es ist die stärkste Identifizierung im Projekt und erreicht das höchste Niveau
`loa3`. Anders als ein Anmeldeverfahren wird es nicht im Konto eingerichtet.

Die eID-Karte liefert die Ausweisdaten, die PIN belegt den Besitzer. `ident-eid` ist das zweite
Tool mit der Rolle `IDENTIFICATION` neben `ident-fsc`. Danach folgt als eigener Schritt die
Zuordnung zu einer Person im Personenverzeichnis, den Stammdaten der Versicherung. Diese Zuordnung
übernimmt `ident-kvnr` ([Verfahren `kvnr`](kvnr.md)).

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat ein einziges Tool, `ident-eid`. Außer den üblichen Identitätsangaben liefert es
die Anschrift und das Pseudonym der Karte als **Claims**, also als Angaben über den Kontoinhaber,
die im Konto gespeichert werden.

| toolId | Rolle | Fassungen |
|---|---|---|
| `ident-eid` | `IDENTIFICATION`, Claims zusätzlich Anschrift (`STREET_ADDRESS`, `POSTAL_CODE`, `LOCALITY`) und das Karten-Pseudonym | 1 |

Anders als `ident-fsc` erbringt es zwei Faktortypen in einem Durchlauf
(`factorTypes={possession,knowledge}`, `maxAcr=loa3`): den Besitz der eID-Karte und das Wissen um
die PIN. Deklariert ist es in `tools/ident_eid/EidToolModule.kt`.

## Ablauf

Wie `ident-fsc` hat das Tool einen einzigen Schritt `input`. Die fehlenden Felder
(`missingFields`) kommen darin gestaffelt:

1. **Kartendaten**: Die eID-Karte liefert ihre vollständigen Ausweisdaten auf einmal:
   `familyName`, `givenNames`, `birthDate`, `streetAddress` (Straße **und** Hausnummer in einer Zeile,
   wie im Kartenfeld `Street`), `postalCode`, `locality` und `restrictedId`. Vorher tippt der Nutzer
   **nichts** ein. Auf einer Karte stehen weder KVNR noch PersonId, also gibt es auch keinen
   Suchschritt davor. Die `restrictedId` ist das Pseudonym, das an die Karte gebunden ist.
2. **`pin`**: die eID-PIN.

Dabei gilt:

- Das Tool prüft die Kartendaten, sobald sie vollständig sind. Erst danach fragt es nach der PIN.
  Geprüft werden nur Form und Vollständigkeit. Einen Abgleich mit dem Personenverzeichnis gibt es
  nicht, denn die Karte selbst ist die Quelle ihrer Daten (siehe unten). Die Prüfung umfasst:
  - Das Geburtsdatum liegt nicht in der Zukunft.
  - Die Postleitzahl hat fünf Ziffern.
  - Die `restrictedId` besteht aus 16 bis 64 Buchstaben und Ziffern.
- Abgelehnte Kartendaten werden samt PIN verworfen. Danach fehlen wieder alle Kartenfelder. Bei
  einer abgelehnten PIN wird nur die PIN verworfen.
- Beide Ablehnungen zählen als Fehlversuch der **Journey**, also des geführten Ablaufs, in dem das
  Tool läuft. Die Antwort nennt nie, welches Feld nicht passte.
- Alle Felder lassen sich auch zusammen in einem einzigen `PATCH` schicken. Ändert der Client
  einzelne Kartenfelder später, prüft das Tool die Kartendaten neu.
- Wie viele Bildschirme ein Client daraus macht, entscheidet er selbst ([Frontend](../10-frontend.md)).
  App und Keycloak zeigen zuerst die Karte, dann die PIN. Nach einem Fehlversuch bleiben sie auf der
  Seite, von der aus abgeschickt wurde. Von der PIN führt „Angaben ändern“ (in Keycloak „Zurück“)
  zur Karte zurück.

## Wer für die Daten einsteht

Der eigentliche Unterschied zu `ident-fsc` liegt darin, wer für die Richtigkeit der Daten
verantwortlich ist.

- Bei `ident-fsc` ist das Personenverzeichnis die Quelle, und das Tool ist nur der Weg dorthin
  (`ClaimSource.PERSON_DIRECTORY`). Der Freischaltcode belegt das Verfahren.
- `ident-eid` bestätigt dagegen auf **eigene** Verantwortung (`ClaimSource(toolId.value)`), was die
  Karte zeigt: Name, Vorname, Geburtsdatum und Adresse als Claims, dazu als siebten Claim die
  `restrictedId`.

Die `restrictedId` ist ein lokaler **Anker**, also eine Angabe, über die sich ein Konto eindeutig
wiederfinden lässt. Über sie wird ein **Interessent** wiedererkannt, also ein Konto, das noch keiner
Person im Personenverzeichnis zugeordnet ist. Eine neue Karte ersetzt den Wert an derselben Stelle,
und ein anderes Konto hat ihn nie (ADR-19). Eine PersonId behauptet `ident-eid` nicht (ADR-18).
Deshalb enthält `Completed.Identified` keinen `PERSON_ID`-Claim.

Nach der Bestätigung zeigt `next` direkt auf `ident-kvnr` ([Verfahren `kvnr`](kvnr.md)). Findet die
Zuordnung nicht statt, ist das Konto ein Interessent mit vollständig bestätigter Identität.

## Fehlerfälle

Zusätzlich zum allgemeinen Vertrag gilt: falsche PIN -> `Failed("eID-PIN ungueltig")`.

## In der Demo

Die Online-Ausweisfunktion ist simuliert (`demoOnly`). Ein echtes Ergebnis käme serverseitig vom
eID-Server.

Die `restrictedId` ist ein Platzhalter für den echten Restricted Identifier. Im Kartenformular lässt
sie sich ändern, obwohl eine echte Karte sie fest mitbringt. Nur so lässt sich in der Demo
durchspielen:

- eine zweite Karte derselben Person (neuer Wert, gleiches Konto, ADR-19),
- dieselbe Karte ein zweites Mal auflegen (Wiedererkennung).

Die eID-PIN hat den Testwert `123456`, entsprechend dem `VALIDCODE` bei `ident-fsc`.
