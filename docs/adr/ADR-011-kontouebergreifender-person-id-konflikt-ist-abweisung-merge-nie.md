# ADR-11: Ein Anker, der schon einem anderen Konto gehört, wird abgewiesen; Konten werden nie automatisch zusammengeführt

**Status:** umgesetzt.

**Kontext**: Ein **Anker** ist eine Angabe, über die sich ein Konto eindeutig wiederfinden lässt.
Beispiele sind die Partnernummer einer Person (`person_id`), eine bestätigte E-Mail-Adresse, die
Mitgliedsnummer (`versnr`) oder die Kennung eines Online-Ausweises (`restricted_id`). Derselbe Wert
darf nie zu zwei Konten gehören, sonst wäre nicht mehr klar, welches Konto gemeint ist (siehe
[Glossar](../glossar/glossar.md)). Es kann aber passieren, dass ein Nutzer in seinem Konto einen Wert
bestätigt, der schon in einem anderen Konto als Anker steht. Diese ADR legt fest, was dann geschieht.

**Entscheidung**: Will ein Konto einen Anker schreiben (`person_id`, `email`, `versnr`,
`restricted_id`), dessen Wert schon einem anderen Konto gehört, weist der Server das ab. Die Antwort
ist `409` mit dem Text „Dieser `<typ>`-Wert gehoert bereits zu einem anderen Konto“. Dabei wird
nichts übernommen: keine Zeile mit der Angabe (Claim) und kein Anker. Konten werden nie automatisch
zusammengeführt; das wäre eine Aufgabe des Betreibers.

Die Datenbank sichert diese Regel für alle Wege ab, auf denen geschrieben wird. Dafür sorgt die
Eindeutigkeitsregel `ux_anchor_value UNIQUE (attribute_type, normalized_value)` auf `account.anchor`
(`account/V2__account.sql`). Die einzige Stelle, die Anker schreibt, ist `AnchorRegistry.bind`
(früher `AccountService.recordAnchor`). Binden zwei Vorgänge gleichzeitig denselben Wert, setzt sich
einer durch. Der andere wird vollständig zurückgerollt und erhält ebenfalls `409`.

Innerhalb **eines** Kontos gilt zusätzlich: Ist die `person_id` einmal gesetzt, lässt sie sich nicht
mehr ändern (`AnchorRule.allowsReplacement = false`). Eine zweite, abweichende Angabe zur
`person_id` wird deshalb ebenfalls abgewiesen.

**Ausnahme**: Ein **verwerfbares** Konto, also eines ohne zugeordnete Person und ohne je
eingerichtetes Anmeldeverfahren, wird mit dem gefundenen Konto zusammengelegt. Wann ein Konto
verwerfbar ist und welche Bedingungen dafür gelten, regelt
[ADR-20](ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md). Zwischen zwei echten
Konten bleibt es bei der Abweisung.

Wie ein Konto über KVNR, Partnernummer oder Mitgliedsnummer gefunden wird, regeln
[ADR-19](ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md) und
[ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md).

**Erwogene Alternative**: Die bestätigte Aussage trotzdem protokollieren und nur verhindern, dass
sie zum gültigen Wert wird. Der Konflikt wäre dann ein Zustand, den man abfragen kann. Oder eine
Warteschlange, in der Menschen den Fall prüfen.

**Begründung**: Zwei verschiedene Menschen dauerhaft miteinander zu verknüpfen ist der teuerste
Fehler, den dieses Modell machen kann. Ihn zu vermeiden ist wichtiger, als die bestätigte Aussage zu
behalten. Es gibt zwar eine Rangfolge, nach der Werte zusammengefasst werden: erst nach der Art des
Ankers, dann nach der Aktualität. Diese Rangfolge gilt aber nur innerhalb eines Kontos, nie über
Kontogrenzen hinweg.

**Folgen und Kosten**: Der betroffene Nutzer kommt nicht von selbst weiter. Solange es keine Funktion
zum Zusammenführen gibt, muss sich der Support um den Fall kümmern.

**Geschichte**: Ursprünglich war `person_id` eine eigene Spalte mit einem Teilindex
`UNIQUE(person_id)`. Seit
[ADR-14](ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md) ist sie ein
gewöhnlicher Anker wie `email`, nur mit dem höchsten Rang. Die Abweisung läuft über denselben Weg wie
für alle Anker. Dabei wurden auch Lücken geschlossen: Der Anker wird geschrieben, bevor irgendetwas
anderes entsteht. Und `resolveByAnchor` prüft die Anker in einer festen Reihenfolge, geordnet nach ihrer
Bindungsstärke. Die Ausnahme für verwerfbare Konten kam mit
ADR-20 hinzu.
