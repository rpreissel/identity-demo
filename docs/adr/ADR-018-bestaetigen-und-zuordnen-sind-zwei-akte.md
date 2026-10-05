# ADR-18: Bestätigen und Zuordnen sind zwei Schritte

**Status:** umgesetzt.

**Kontext**: Bei einer **Identifizierung** stellt das System fest, wer jemand wirklich ist, etwa mit
dem Online-Ausweis (eID) oder über den Dienst Nect. Danach soll das Konto möglichst einer **Person**
im **Personenverzeichnis** zugeordnet sein, also einem Datensatz in den Stammdaten der Versicherung
mit Partnernummer (siehe [Glossar](../glossar/glossar.md)). Ein Ausweis enthält aber weder die KVNR
(Krankenversichertennummer) noch die Partnernummer. Aus dem Ausweis allein lässt sich die Person im
Verzeichnis also nicht finden. Früher hat ein einziges Tool beides erledigt und dabei eine eingetippte
Nummer so behandelt, als stamme sie vom Ausweis. Diese ADR trennt das Bestätigen der Identität vom
Zuordnen zu einer Person.

## Entscheidung

Eine Identifizierung mit einem Ausweisdokument besteht aus zwei Schritten mit zwei Tools.

**Bestätigen.** Das Tool `ident-eid` bestätigt nur, was auf dem Dokument steht. Für `ident-nect` gilt
dasselbe. Das Tool bestätigt diese Angaben in eigener Verantwortung (`ClaimSource(toolId.value)`).
Bei `ident-eid` sind das sieben Angaben (Claims):

- `FAMILY_NAME`, `GIVEN_NAMES` und `BIRTH_DATE`,
- `STREET_ADDRESS` (die ganze Straßenzeile mit Hausnummer), `POSTAL_CODE` und `LOCALITY`,
- das Kartenpseudonym `EID_RESTRICTED_ID`.

Eine Person im Personenverzeichnis findet dieser Schritt nicht, denn auf einem Ausweis stehen weder
KVNR noch Partnernummer.

**Zuordnen.** Die Zuordnung zu einer Person im Personenverzeichnis übernimmt `ident-kvnr`. Das Tool
fragt nach der Versichertennummer (KVNR); wer keine hat, gibt stattdessen die Partnernummer an (siehe
[ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)). Mit dieser Nummer sucht es über
`PersonDirectory` die Person. Erst dann behauptet es:

- `PERSON_ID` (die Partnernummer),
- `KVNR`, wenn eine KVNR angegeben wurde,
- `MEMBER_NUMBER`, wenn die Person bei uns versichert ist.

Alle drei Angaben haben die Quelle `ClaimSource.PERSON_DIRECTORY`, denn für diese Werte ist das
Personenverzeichnis maßgeblich.

Der zweite Schritt folgt direkt auf den ersten (`RegisterState.Assigning`, `next` zeigt auf
`ident-kvnr`). Eine Ja/Nein-Frage vorher gibt es nicht. Wer den Schritt abbricht („Jetzt nicht“) oder
eine unbekannte Nummer angibt, bekommt keinen Fehler. Er ist danach ein vollständig bestätigter
**Interessent**, also jemand mit bestätigter Identität, dessen Konto keiner Person zugeordnet ist
(siehe [ADR-10](ADR-010-interessent-ist-konto-zustand-kein-eigener-authintent.md)).

**Rolle.** `ident-kvnr` hat die eigene Rolle `ToolRole.CORRELATION`. Damals war es in der Kategorie
`IDENT`; Kategorien gibt es seit 2026-09-30 nicht mehr, Entscheidungen fragen heute die Rolle ab. Die
Rolle sagt ausdrücklich, dass das Tool für sich nichts beweist:

- Es gehört zu keiner Nachweisart (`evidenceAxis()` liefert `null`).
- Es hebt also weder das IAL (wie sicher die Identität feststeht) noch das AAL (wie sicher die
  Anmeldung ist).
- Seine `factorTypes` sind leer.

Das IAL eines bestätigten Interessenten stammt allein aus dem Bestätigen. Die Auswahl der Tools, die
angeboten werden (`CandidateTools.forIdentification`, `forAssignment`,
`AuthPolicy.reIdentCandidates`), prüft die Rolle. So wird `ident-kvnr` nie als Weg zur Identifizierung
oder zur erneuten Identifizierung angeboten.

## Worauf die Sicherheit beruht

Eine eingetippte Nummer ist kein Nachweis. Die Sicherheit von `ident-kvnr` beruht deshalb auf zwei
Prüfungen:

- `requires`: `FAMILY_NAME`, `GIVEN_NAMES` und `BIRTH_DATE` müssen am Konto schon bestätigt sein.
  Sonst lässt sich das Tool nicht einmal starten.
- `IdentityResolver.attestedIdentityMatches`: Bevor ein Anker geschrieben wird, prüft dieser Abgleich,
  ob die Stammdaten hinter der Nummer zu der bereits bestätigten Identität passen. (Ein **Anker** ist
  eine Angabe, über die sich ein Konto eindeutig wiederfinden lässt.)

Ohne diesen Abgleich könnte jemand mit der eigenen eID eine fremde Nummer eintippen. Er könnte so den
`PERSON_ID`-Anker dieser Person an sein eigenes Konto binden. Eine unbekannte Nummer ergibt einen
Interessenten; das ist kein Konflikt. Eine bekannte Nummer mit widersprechenden Daten ergibt `409`.

## Begründung

Vorher erledigte `ident-eid` beides und behauptete dabei etwas Falsches:
`ClaimDeclaration(PERSON_ID, ClaimSource(toolId.value))`. Das Verfahren gab also eine PersonId als
von ihm bestätigt aus, die es nie von der Karte gelesen hatte. Der Controller hatte sie vorher über
eine eingetippte KVNR nachgeschlagen. Außerdem ließ sich der Fall „gültige eID, aber kein Eintrag im
Personenverzeichnis“ nicht abbilden. Er endete mit einem Fehler, obwohl ADR-10 genau diesen Zustand
eines Kontos vorsieht.

`ATTEST` (damals eine Kategorie, heute die Rolle `ATTESTATION`) wäre für das Bestätigen durch die
Karte falsch. Denn diese Rolle trägt per Definition nichts zu ACR und AMR bei, also weder zum Niveau
noch zur Liste der Verfahren. Eine eID trägt aber zum IAL bei. Sonst stünde der stark bestätigte
Interessent auf `loa1` statt auf `loa3`.

**Erwogene Alternative:** Alles in einem Tool lassen und nur die Fehlermeldung verbessern.

## Folgen

- Es gibt ein Tool, ein Modul und einen Schritt mehr.
- `ToolOutcome.Completed.Identified` enthält höchstens eine `PERSON_ID`-Angabe statt genau einer.
  Jeder Aufrufer muss deshalb den Fall ohne PersonId behandeln.
- `ident_eid` nutzt keinen Port zur Personensuche mehr.

## Namensvetter: Adresse nur bei einem Namensvetter im Register (entschieden 2026-09-26)

Bei der Zuordnung über KVNR oder Partnernummer (`IdentityResolver.attestedIdentityMatches`) müssen
Name, Vorname und Geburtsdatum **alle** bezeugt sein und zum Register passen. Fehlt eine dieser
Angaben, wird sie nicht übersprungen. Kennt das Register eine zweite Person mit gleichem Namen,
Vornamen und Geburtsdatum (`PersonDirectory.hasNamesake`), müssen zusätzlich Straße, PLZ und Ort
bezeugt sein und passen. Das kann scheitern: wenn keine Adresse bezeugt ist (Reisepass über Nect)
oder wenn die Adresse im Register veraltet ist. Dann bleibt nur der Brief mit dem Freischaltcode.

Die KVNR hilft nicht, Namensvettern zu unterscheiden. Sie ist kein Geheimnis: Sie steht auf der
Karte, und Arztpraxen kennen sie. Die Adresse immer zu vergleichen, hätte vor allem echte Personen
abgewiesen. Denn Ausweis und Register veralten unterschiedlich schnell (etwa nach einem Umzug) und
schreiben Straßen verschieden.

**Restrisiko, bewusst getragen:** Steht der Namensvetter selbst nicht im Register, weil er nicht bei
uns versichert ist, sieht das Register keinen Konflikt. Er kann sich dann mit seiner eigenen eID und
der fremden KVNR der Person im Register zuordnen. Hat diese Person schon ein Konto, übernimmt er es.
Sonst bindet er die Person an ein neues Konto. Bei häufigen Namen gibt es in Deutschland Hunderte
solcher Paare je Name, und ein Angreifer kann gezielt eines suchen. Wer dieses Restrisiko nicht
eingehen will, lässt die Adresse immer vergleichen. Das ist eine Zeile in `IdentityMatchingService`.

## Geschichte

- Erst diese ADR machte `requires` wirksam. Vorher war `DefaultAuthPolicy.requiresSatisfied` fest auf
  `EMAIL` programmiert, und `CandidateTools.forIdentification` beachtete `requires` gar nicht. Heute
  prüft die Funktion allgemein gegen `AccountProfile.establishedClaims`, also gegen die Angaben minus
  die Widerrufe (siehe [ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md)).
- Die erste Fassung sagte, `ident-kvnr` trage zum IAL bei und `evidenceAxis()` werfe bei `ATTEST`
  einen Fehler. Beides stimmte nicht; beide Rollen liefern `null`.
- Die Hausnummer war anfangs eine eigene Angabe. Seit 2026-09-24 steht sie in `STREET_ADDRESS`, so
  wie eID und PID die Straßenzeile liefern.
- Seit ADR-34 ordnet `ident-kvnr` auch über die Partnernummer zu. Dann entsteht keine Angabe zur KVNR.
