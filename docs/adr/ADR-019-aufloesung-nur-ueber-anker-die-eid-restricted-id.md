# ADR-19: Konten werden nur über Anker gefunden — auch die `restricted_id` der eID ist einer

**Status:** umgesetzt.

**Kontext**: Nach einer **Identifizierung**, etwa mit dem Online-Ausweis (eID), muss das System
entscheiden: Gehört diese Person schon zu einem bestehenden Konto, oder ist sie neu? Dafür sucht es
das passende Konto. Ein **Anker** ist eine Angabe, über die sich ein Konto eindeutig wiederfinden
lässt, etwa die Partnernummer oder eine bestätigte E-Mail-Adresse. Derselbe Wert gehört nie zu zwei
Konten (siehe [Glossar](../glossar/glossar.md)). Früher suchte das System zusätzlich über Name,
Vorname und Geburtsdatum in den Angaben aller Konten. Diese Suche war unsicher und lieferte manchmal
kein eindeutiges Ergebnis. Diese ADR legt fest, dass nur noch über Anker gesucht wird. Sie legt auch
fest, wie jemand, der sich mit der eID identifiziert hat, ohne Partnernummer wiedererkannt wird.

**Entscheidung**: `IdentityMatchingService.resolve` sucht zu einer bestätigten Identität das Konto
**nur über lokale Anker** (`resolveByAnchor`). Die Anker werden dabei in der Rangfolge von
`AnchorRule.bindingStrength` geprüft. Lokale Anker sind heute:

- `PERSON_ID`,
- `MEMBER_NUMBER`,
- `EID_RESTRICTED_ID`,
- `NECT_RESTRICTED_ID` (das Kartenpseudonym, wenn Nect die Karte liest, § 18 PAuswG),
- `EMAIL`.

Passt keiner, lautet das Ergebnis `Unresolved`.

Die frühere zweite Stufe ist entfernt, samt ihrem Ergebnis `Resolution.Ambiguous`. Sie hatte die
Kombination aus Name, Vorname und Geburtsdatum mit der Historie der Angaben aller Konten verglichen.

Um Interessenten mit eID wiederzuerkennen, dient stattdessen die `restricted_id` der Karte.
(Ein **Interessent** ist jemand, dessen Konto keiner Person im Personenverzeichnis zugeordnet ist.)
Das Tool `ident-eid` meldet die `restricted_id` als Angabe (Claim). Sie ist ein an die Karte
gebundenes Pseudonym; in der Demo steht sie stellvertretend für den echten Restricted Identifier. Sie
ist ein lokaler Anker (`AttributeAuthority.Local`) mit `AnchorAcrFloor(LOA2, LOA2)` und
`allowsReplacement = true`. Das heißt: Eine neue Karte bringt einen neuen Wert, und der ersetzt den
alten an derselben Stelle, genau wie bei `EMAIL`. Der ersetzte Wert wird dabei widerrufen (Auslöser
„Anker ersetzt“ in [ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md)). Gehört der
Wert einem anderen Konto, wird er abgewiesen (siehe
[ADR-11](ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md)).

Der Abgleich von Name, Vorname und Geburtsdatum bleibt dort, wo er fachlich hingehört: als **Prüfung
gegen `personenverzeichnis`**, ob die Angaben zur gefundenen Person passen. Er wird nie als Suche über
die Angaben der Konten benutzt. Zwei Stellen nutzen diese Prüfung:

- `ident-fsc` prüft die Person, die es über die KVNR gefunden hat, selbst gegen Name, Vornamen und
  Geburtsdatum (`matchesPersonalDetails`). Erst danach meldet es die KVNR.
- `attestedIdentityMatches` vergleicht die Stammdaten hinter der Nummer mit der bestätigten
  Identität. Das geschieht, bevor der Anker der Zuordnung geschrieben wird.

**Erwogene Alternative**: Die Suche über die Kombination der Attribute behalten. Sie würde dann aber
nur zusammen mit dem Vergleich der KVNR wirken und gegen `personenverzeichnis` statt gegen die Konten
prüfen.

**Begründung**:

- Die Angaben eines Kontos sagen, woher ein Wert kam. Maßgeblich wie das Personenverzeichnis sind sie
  nicht. Ein Treffer in allen drei Attributen kann dieselben Daten in fremden Konten finden. Damit war
  er schwächer als das, was er ersetzen sollte.
- Der Abgleich gegen `personenverzeichnis` schützt schon. Eine zweite Suche über die Historie der
  Konten wäre überflüssig. Sie brächte nur den Ausgang `Ambiguous` in die Journey, der nie sauber
  festgelegt war.
- Die `restricted_id` ist das richtige Merkmal, um Interessenten mit eID wiederzuerkennen. Sie ist an
  die Karte gebunden, bei einem neuen Ausweis neu, aber nie für zwei Personen gleich. Genau das leistet
  ein ersetzbarer Anker.
- Das passt auch zur EUDI-Wallet: Deren echte PID käme als eigene Art von Anker hinzu.

**Folgen und Kosten**: Bestätigungen aus der Zeit vor dieser ADR enthalten keine `restricted_id`.
Solche Bestätigungen erkennt das System nicht wieder. Sie enden bei `Unresolved` und damit bei einem
neuen Konto. Die `restricted_id` wird bewusst **nicht** nach Keycloak gespiegelt: Sie ist kein
Stammdatum, nur ein Anker zum Wiedererkennen. Seit [ADR-38](ADR-038-keycloak-liest-konten.md) wird
ohnehin nichts mehr gespiegelt. Keycloak liest das Konto, und die `restricted_id` gehört nicht zu den
Werten, die es liest.

**Geschichte**: Mit dieser ADR wurden entfernt: `Resolution.Ambiguous`, `MatchedVia.Attributes`,
`BindingStrength.ATTRIBUTE_COMBINATION`, `findAccountIdsMatchingAllThree` und der Index
`ix_claim_type_value`. Die Hausnummer war anfangs eine eigene Angabe; inzwischen ist sie Teil von
`STREET_ADDRESS`. `MEMBER_NUMBER` kam mit
[ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md) als weiterer lokaler Anker hinzu.
