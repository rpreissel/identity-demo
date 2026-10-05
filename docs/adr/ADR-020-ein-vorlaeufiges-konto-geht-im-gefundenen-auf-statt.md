# ADR-20: Ein vorläufiges Konto geht im gefundenen auf, statt den Lauf abzuweisen

**Status:** umgesetzt.

**Kontext**: Während einer **Journey**, also eines geführten Ablaufs wie der Registrierung, arbeitet
das System immer mit einem bestimmten Konto. Manchmal legt es dieses Konto nur nebenbei an, etwa
direkt nach einer Identifizierung, bei der noch kein passendes Konto gefunden wurde. Ein solches
Konto ist **vorläufig** (im [Glossar](../glossar/glossar.md) „verwerfbar“): Es ist keiner Person
zugeordnet, und es wurde nie ein Anmeldeverfahren darin eingerichtet. Ein späterer Schritt findet dann
womöglich doch ein anderes, schon bestehendes Konto. Der Grund ist ein **Anker**, also eine Angabe,
über die sich ein Konto eindeutig wiederfinden lässt. Nach
[ADR-11](ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md) darf derselbe
Anker nie zu zwei Konten gehören, und der Schritt würde mit `409` abgewiesen. Der Nutzer hätte diesen
Konflikt aber weder verursacht, noch könnte er ihn lösen. Diese ADR regelt, was in diesem Fall
geschieht.

## Entscheidung

Findet ein Nachweis ein **anderes** Konto als das, mit dem die Journey gerade arbeitet, dann wird das
vorläufige der beiden Konten in das andere übernommen. Das heißt: Seine Daten werden in das andere
Konto übertragen, und danach wird es gelöscht. Welches der beiden Konten vorläufig ist, spielt keine
Rolle:

- **Das Konto der Journey ist vorläufig.** Dann wechselt die Journey zum gefundenen Konto und
  übernimmt die Bestätigung dorthin. Das ist der Fall, in dem zuerst identifiziert wird:
  `ident-eid` bestätigt die Identität und findet niemanden. `performRecordIdentification` legt dafür
  ein Konto an. Der Schritt `ident-kvnr` danach findet das echte Konto.
- **Das gefundene Konto ist vorläufig.** Dann bleibt die Journey bei ihrem Konto und übernimmt die
  Daten des gefundenen. Das ist der Fall im Experiment „Erst Anmeldeverfahren einrichten“: Die
  Journey arbeitet mit dem echten Konto, in dem gerade die Anmeldeverfahren entstanden sind. Die
  Identifizierung findet über den Anker `restricted_id` ein übrig gebliebenes Konto aus einem
  früheren, abgebrochenen Versuch.
- **Keines der beiden ist vorläufig.** Dann bleibt es beim `409`. Zwei echte Konten werden nicht
  nebenbei zusammengelegt (siehe
  [ADR-11](ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md)).

**Vorläufig** ist eine benannte Regel an `AccountProfile`. Sie heißt `isDisposable` (im Glossar
„verwerfbar“) und gilt, wenn beides zutrifft: `isUnidentified` (keine PersonId) **und** nie ein
Anmeldeverfahren eingerichtet. Abgeschaltete Verfahren zählen dabei mit. Denn auf eine widerrufene
Instanz verweisen weiterhin Angaben, um ihre Herkunft zu belegen (`account.claim.auth_method_id`,
siehe [ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md)). Dieselbe Regel entschied
früher auch, ob eine abgebrochene Journey ihr Konto löschen darf
(`JourneyService.deleteIfAbandonedUnidentified`). Seit [ADR-46](ADR-046-konto-im-aufbau.md)
entscheidet darüber, ob das Konto im Aufbau ist (`JourneyService.discardIfBeingSetUp`); siehe den
Nachtrag unten.

### Bestätigte Adresse: zwei Bedingungen mehr

Die Regel gilt auch für eine E-Mail-Adresse, die mit `confirm-email` bestätigt wurde. Wer den Besitz
der Adresse beweist, beweist einen Wert, über den Konten gefunden werden (`resolveByAnchor`, derselbe
Weg wie bei `auth-email-lookup`). Ein Postfach sagt aber nur „dieses Postfach gehört mir“, nie „ich
bin diese Person“. Deshalb kommen zwei Bedingungen hinzu:

1. **Diese Sitzung muss die Identität schon nachgewiesen haben**
   (`JourneyActionExecutor.accountOfAttestation`). Fehlt ein Nachweis der Art `IDENTITY`, antwortet
   sie mit `409` „Diese Adresse gehört bereits zu einem Konto. Melden Sie sich damit an, statt sich
   neu zu registrieren.“ Ohne diese Bedingung könnte eine neue Sitzung die hinterlegte Adresse eines
   anderen erneut bestätigen. Sie könnte so dessen Konto samt Anmeldeverfahren übernehmen.
2. **Die bestätigte Identität muss zum gefundenen Konto passen.** Ist das Konto einer Person im
   Personenverzeichnis zugeordnet, prüft `IdentityResolver.attestedIdentityMatches` die in dieser
   Sitzung bestätigte Identität gegen deren Stammdaten. Das ist dieselbe Prüfung wie vor der
   Zuordnung (siehe [ADR-18](ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md)).

Eine vorherige Prüfung „Ist die Adresse schon vergeben?“ gibt es nicht. Vor der Eingabe des Codes ist
nichts bewiesen, und die Ablehnung traf regelmäßig genau die richtige Person. Aus Bedingung 1 folgt:
Im Experiment „Erst Anmeldeverfahren einrichten“ kommt `confirm-email` vor jeder Identifizierung.
Eine bereits vergebene Adresse wird dort deshalb immer abgewiesen.

### Wie übernommen wird

Die Übernahme (`AccountService.absorbDisposableAccount`) verschiebt **nicht** allgemein
Identitätsdaten zwischen Konten. Sie verlangt, dass das Quellkonto vorläufig ist, und genau das macht
sie harmlos.

Die Reihenfolge der Übernahme gehört zur Entscheidung. Jeder Anker in `account.anchor` ist je Typ und
Wert über alle Konten eindeutig (`ux_anchor_value`). Deshalb werden die Anker der Quelle zuerst
gelesen, freigegeben und gelöscht, **bevor** dieselben Werte am Zielkonto geschrieben werden.
Geschrieben wird auf dem normalen Weg über `recordClaim`, eine Angabe nach der anderen in der
ursprünglichen Reihenfolge. Konfliktprüfung, Mindestniveaus der Anker und Widerrufsregeln gelten also
unverändert.

Für den Kanal gilt dasselbe in umgekehrter Richtung: Nachweis und Geräteverknüpfung werden auf das
neue Konto umgestellt (`SessionEvidenceService.rebindToAccount`, `linkDeviceToAccount`), **bevor** das
alte Konto gelöscht wird. Was die Sitzung bewiesen hat, bleibt bewiesen; nur die zwischengespeicherten
Tokens entfallen. Danach läuft die Registrierung noch einmal durch
`RegisterStrategy.afterIdentification`. Dort wird geprüft: Ist das Gerät schon mit einem anderen
Konto verknüpft? Kann das Konto ein vorhandenes Verfahren nachweisen, statt ein neues einzurichten?

## Begründung

Das vorläufige Konto entsteht nur nebenbei in der Journey. Außer der gerade entstandenen Bestätigung
enthält es nichts. Es in das gefundene Konto zu übernehmen, kostet nichts und bewahrt genau diese
Bestätigung. Ließe man es stehen, entstünde ein Konflikt, den der Nutzer weder verursacht hat noch
auflösen kann.

**Erwogene Alternative:** Die Angaben aus der eID bis zur Zuordnung nur in der Journey halten. Sie
stünden dann in einer JSON-Spalte an `auth_journey`, und ein künstliches `AccountProfile` würde sie
mit den Daten des Kontos zusammen zeigen. Verworfen, weil das `requires` von `ident-kvnr` gegen
`ctx.account` geprüft wird. Ohne gespeichertes Konto ließe sich die Zuordnung gar nicht anbieten. Das
wäre viel Aufwand gegen einen Fehler, der nur aus einem `409` besteht.

## Folgen

- Zwei echte Konten zusammenzuführen bleibt ungelöst; es bleibt beim `409`.
- Hat das Zielkonto eine andere E-Mail-Adresse oder `restricted_id`, gelten die normalen Regeln für
  Anker. Der Wert wird entweder ersetzt und der alte widerrufen (`EMAIL`, `EID_RESTRICTED_ID`), oder
  er wird abgewiesen (`PERSON_ID`).
- Zurückgenommene Angaben werden nicht übernommen. Übernommene Zeilen im Log der Angaben (Claim-Log)
  haben den Zeitpunkt der Übernahme. Wann die Identität bewiesen wurde, steht weiterhin in
  `account.change_log` (IDENTIFIED). Dort steht der ursprüngliche Zeitpunkt in `occurred_at` und der
  Vermerk `carriedFromAccountId` in `details`.

## Geschichte

Bis Commit e92716c (2026-09-23) genügte bei der bestätigten Adresse, dass eines der beiden Konten
vorläufig war und die Identität übereinstimmte. Dann zeigte sich, dass eine neue Sitzung so ein
fremdes Konto übernehmen konnte. Deshalb kam die Bedingung hinzu, dass die Sitzung die Identität
schon nachgewiesen haben muss.

**Nachtrag 2026-09-27.** Seit [ADR-46](ADR-046-konto-im-aufbau.md) verwirft ein Abbruch ein Konto
ohne Anmeldeverfahren vollständig, auch ein identifiziertes. Was dann noch übrig ist, löscht die
Aufbewahrung. Die Regel dieser ADR bleibt trotzdem gültig: für gleichzeitige Versuche und für ein
Konto, das noch Verfahren aus einem früheren, abgebrochenen Versuch hat.
