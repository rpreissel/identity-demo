# Verfahren `eid`

Identifizieren mit der Online-Ausweisfunktion: Die eID-Karte liefert ihre Ausweisdaten, die PIN
belegt den Besitzer. `ident-eid` ist das zweite `IDENTIFICATION`-Tool neben `ident-fsc`. Die
Zuordnung zu einer Person im Personenverzeichnis folgt danach als eigener Schritt über `ident-kvnr`
([Verfahren `kvnr`](kvnr.md)).

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `ident-eid` | `IDENTIFICATION`, Claims zusätzlich Anschrift (`STREET_ADDRESS`, `POSTAL_CODE`, `LOCALITY`) und das Karten-Pseudonym | 1 |

Anders als `ident-fsc` erbringt es zwei Faktortypen in einem Durchlauf
(`factorTypes={possession,knowledge}`, `maxAcr=loa3`): den Besitz der eID-Karte und das Wissen um
die PIN. Deklariert in `tools/ident_eid/EidToolModule.kt`.

## Ablauf

Wie `ident-fsc` hat es einen einzigen Schritt `input` mit gestaffelten `missingFields`:

1. **Kartendaten**: Die eID-Karte liefert ihre vollständigen Ausweisdaten auf einmal:
   `familyName`, `givenNames`, `birthDate`, `streetAddress` (Straße **und** Hausnummer in einer Zeile,
   wie im Kartenfeld `Street`), `postalCode`, `locality` und `restrictedId`. Vorher wird **nichts** eingetippt: Eine
   Karte trägt weder KVNR noch PersonId, also gibt es auch keinen Suchschritt davor. Die
   `restrictedId` ist das an die Karte gebundene Pseudonym.
2. **`pin`**: die eID-PIN.

Dabei gilt:

- Die Kartendaten werden geprüft, sobald sie vollständig sind; erst danach fragt das Tool nach der
  PIN. Geprüft werden nur Form und Vollständigkeit, kein Abgleich mit dem Personenverzeichnis, denn
  die Karte steht für ihre Daten selbst ein (siehe unten): Das Geburtsdatum liegt nicht in der
  Zukunft, die Postleitzahl hat fünf Ziffern, die `restrictedId` besteht aus 16 bis 64 Buchstaben
  und Ziffern.
- Abgelehnte Kartendaten werden samt PIN verworfen; danach fehlen wieder alle Kartenfelder. Bei
  einer abgelehnten PIN wird nur die PIN verworfen.
- Beide Ablehnungen zählen als Fehlversuch der Journey. Die Antwort nennt nie, welches Feld nicht
  passte.
- Alle Felder lassen sich auch zusammen in einem einzigen `PATCH` schicken. Wer einzelne
  Kartenfelder später ändert, löst eine neue Prüfung der Kartendaten aus.
- Wie viele Bildschirme ein Client daraus macht, entscheidet er selbst ([Frontend](../10-frontend.md)).
  App und Keycloak zeigen zuerst die Karte, dann die PIN, und bleiben nach einem Fehlversuch auf der
  Seite, von der aus abgeschickt wurde. Von der PIN führt „Angaben ändern“ (in Keycloak „Zurück“)
  zur Karte zurück.

## Wer für die Daten einsteht

Der eigentliche Unterschied zu `ident-fsc` liegt darin, wer für die Daten einsteht. Bei
`ident-fsc` ist das Personenverzeichnis die Quelle und das Tool nur der Weg dorthin
(`ClaimSource.PERSON_DIRECTORY`); der Freischaltcode belegt das Verfahren. `ident-eid` bestätigt
dagegen auf **eigene** Verantwortung (`ClaimSource(toolId.value)`), was die Karte zeigt: Name,
Vorname, Geburtsdatum und Adresse als Claims, dazu als siebten Claim die `restrictedId`. Sie ist
ein lokaler Anker, über den ein Interessent wiedererkannt wird (ADR-19: Eine neue Karte ersetzt den
Wert an derselben Stelle, ein fremdes Konto hält ihn nie). Eine PersonId behauptet `ident-eid` nicht
(ADR-18); `Completed.Identified` enthält deshalb keinen `PERSON_ID`-Claim.

Nach der Bestätigung zeigt `next` direkt auf `ident-kvnr` ([Verfahren `kvnr`](kvnr.md)). Bleibt die
Zuordnung aus, ist das Konto ein Interessent mit vollständig bestätigter Identität.

## Fehlerfälle

Zusätzlich zum allgemeinen Vertrag: falsche PIN -> `Failed("eID-PIN ungueltig")`.

## In der Demo

Die Online-Ausweisfunktion ist simuliert (`demoOnly`); ein echtes Ergebnis käme serverseitig vom
eID-Server. Die `restrictedId` ist ein Platzhalter für den echten Restricted Identifier. Im
Kartenformular lässt sie sich ändern, obwohl eine echte Karte sie fest mitbringt. Nur so lässt sich
in der Demo eine zweite Karte derselben Person durchspielen (neuer Wert, gleiches Konto, ADR-19)
oder dieselbe Karte ein zweites Mal auflegen (Wiedererkennung). Die eID-PIN hat den Testwert
`123456`, entsprechend dem `VALIDCODE` bei `ident-fsc`.
